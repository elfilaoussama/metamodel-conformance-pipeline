"""Linux sequential analysis batches with receipts, worker recycling and bounded retries.

Job states describe execution, never replace the capsule's formal decisions.
The engine JAR is unchanged. No concurrent Alloy calls or cross-input result cache.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import os
import shutil
import signal
import subprocess
import time
import tempfile
import fcntl
from contextlib import contextmanager


def atomic_json(path, data):
    descriptor,name=tempfile.mkstemp(prefix='.'+path.name+'-',dir=path.parent)
    temporary=Path(name)
    try:
        with os.fdopen(descriptor,'w',encoding='utf-8') as stream:
            stream.write(json.dumps(data,indent=2)+'\n');stream.flush();os.fsync(stream.fileno())
        temporary.replace(path)
        descriptor=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(descriptor)
        finally:os.close(descriptor)
    finally:
        if temporary.exists():temporary.unlink()


def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):h.update(block)
    return h.hexdigest()


def durable_mkdir(path,exist_ok=False):
    missing=[];ancestor=path
    while not ancestor.exists():missing.append(ancestor);ancestor=ancestor.parent
    path.mkdir(parents=True,exist_ok=exist_ok)
    # Persist both a new directory and its entry in each newly created parent.
    for directory in missing+[ancestor]:
        descriptor=os.open(directory,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(descriptor)
        finally:os.close(descriptor)


@contextmanager
def batch_lease(output):
    descriptor=os.open(output/'batch.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    try:
        try:fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise ValueError('batch still has a live supervisor or worker')
        yield descriptor
    finally:
        # Do not LOCK_UN: a worker inherits this open-file description, so the
        # kernel must retain the lease until the final owning process closes it.
        os.close(descriptor)


def checked_artifacts(output,status,phase):
    names={'observation.xmi'} if phase=='observe' else {'observation.xmi','repository-instance.als','verification-capsule.json'}
    recorded=status.get('artifacts')
    if status.get('phase')!=phase or not isinstance(recorded,dict) or set(recorded)!=names:
        raise ValueError('terminal receipt lacks the required artifact binding')
    for name,h in recorded.items():
        path=Path(output)/name
        if path.is_symlink() or not path.is_file() or digest(path)!=h:raise ValueError('completed artifact changed: '+name)
    return recorded


def load_jobs(path):
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or set(data) != {'schemaVersion', 'jobs'} or data['schemaVersion'] != 1:
        raise ValueError('expected schemaVersion 1 and jobs')
    if not isinstance(data['jobs'], list) or not data['jobs']:
        raise ValueError('jobs must be a nonempty list')
    jobs, ids = [], set()
    for item in data['jobs']:
        allowed = {'id', 'source', 'language', 'externalParents', 'dependencyJars', 'dependencyManifest'}
        if not isinstance(item, dict) or set(item) - allowed or not {'id', 'source', 'language'} <= set(item):
            raise ValueError('invalid job fields')
        if any(not isinstance(item[k], str) or not item[k].strip() for k in ('id', 'source', 'language')):
            raise ValueError('id, source and language must be nonempty strings')
        if item['id'] in ids or item['language'] not in ('java', 'python', 'cpp'):
            raise ValueError('duplicate id or unsupported language')
        ids.add(item['id'])
        job = dict(item)
        for field in ('externalParents', 'dependencyJars'):
            value = job.get(field, [])
            if not isinstance(value, list) or any(not isinstance(x, str) or not x.strip() for x in value):
                raise ValueError(field + ' must be a list of nonempty strings')
            job[field] = value
        if job.get('dependencyManifest') is not None:
            if not isinstance(job['dependencyManifest'], str) or not job['dependencyManifest'].strip():
                raise ValueError('dependencyManifest must be a nonempty path')
            if job['dependencyJars']:
                raise ValueError('dependencyManifest and dependencyJars cannot be combined')
        def resolve(value): return str((path.parent / value).resolve())
        job['source'] = resolve(job['source'])
        job['dependencyJars'] = [resolve(x) for x in job['dependencyJars']]
        if job.get('dependencyManifest') is not None:
            job['dependencyManifest'] = resolve(job['dependencyManifest'])
        jobs.append(job)
    return jobs


def invocation(job, output, phase='analyze'):
    values = [phase, '--source', job['source'], '--language', job['language'], '--output', str(output)]
    for name, field in (('external-parent', 'externalParents'), ('dependency-jar', 'dependencyJars')):
        for value in job.get(field, []): values.extend(['--' + name, value])
    if job.get('dependencyManifest') is not None:
        values.extend(['--dependency-manifest', job['dependencyManifest']])
    return values


def terminate(process):
    if process.poll() is None:
        try: os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError: pass
    process.wait()


def run_jobs(jobs, output, command_factory, jobs_per_worker, job_timeout, worker_retries,
             *, phase='analyze', expected_observations=None, initial_summary=None, lease_fd=None):
    """command_factory is a testable process boundary; the CLI uses only the Java worker."""
    if phase not in ('analyze','observe'):raise ValueError('invalid worker phase')
    summary = initial_summary if initial_summary is not None else dict(schemaVersion=2,phase=phase,
        jobs=[dict(id=j['id'],state='PENDING',attempts=[]) for j in jobs],workers=[])
    rows=summary['jobs']
    for row in rows:
        if row['state']=='PENDING' and sum(a['state']=='UNFINISHED' for a in row['attempts'])>worker_retries:
            row['state']='UNFINISHED'
    def save(): atomic_json(output/'summary.json', summary)
    def new_directory(parent,prefix,ordinal):
        # mkdir can survive a hard loss before its schedule/summary publication.
        # Retain such a directory; never overwrite it or mistake it for a result.
        while True:
            candidate=parent/('%s-%04d'%(prefix,ordinal))
            try:durable_mkdir(candidate);return candidate
            except FileExistsError:
                referenced={w['directory'] for w in summary['workers']} if prefix=='worker' else {
                    str(Path(a['output']).parent.relative_to(output)) for row in rows for a in row['attempts'] if 'output' in a}
                name=candidate.name if prefix=='worker' else str(candidate.relative_to(output))
                if name not in referenced:
                    orphans=summary.setdefault('unreconciledDirectories',[])
                    if str(candidate.relative_to(output)) not in orphans:orphans.append(str(candidate.relative_to(output)))
                ordinal+=1
    save()
    while any(row['state'] == 'PENDING' for row in rows):
        indices = [i for i, row in enumerate(rows) if row['state'] == 'PENDING'][:jobs_per_worker]
        folder = new_directory(output,'worker',len(summary['workers']))
        schedule, attempts = [], {}
        for i in indices:
            attempt = dict(state='SCHEDULED', worker=folder.name)
            rows[i]['attempts'].append(attempt)
            directory = new_directory(output/'jobs'/('%04d'%i),'attempt',len(rows[i]['attempts']))
            attempt['output'] = str(directory/'result')
            attempts[i] = attempt
            item=dict(receipt=str(folder/('%04d.json' % i)),output=attempt['output'],batchRoot=str(output),
                      arguments=invocation(jobs[i],attempt['output'],phase))
            if expected_observations is not None:
                expected=expected_observations[jobs[i]['id']]
                if digest(expected['path'])!=expected['sha256']:raise ValueError('prepared observation changed')
                item.update(expectedObservation=expected['path'],expectedObservationSha256=expected['sha256'])
            schedule.append(item)
        schedule_path = folder/'schedule.json'
        atomic_json(schedule_path, schedule)
        worker = dict(directory=folder.name, jobs=indices,scheduleSha256=digest(schedule_path),state='SCHEDULED')
        summary['workers'].append(worker)
        save()
        started, active, active_since, reason = time.monotonic(), None, None, None
        idle_since = started
        with (folder/'worker.log').open('w') as log:
            try:
                proc = subprocess.Popen(command_factory(schedule_path, folder), stdout=log,
                                        stderr=subprocess.STDOUT, start_new_session=True,
                                        pass_fds=() if lease_fd is None else (lease_fd,))
                worker.update(state='RUNNING',pid=proc.pid);save()
            except OSError as error:
                worker.update(exit=None, reason='WORKER_STARTUP_ERROR', error=str(error))
                for row in rows:
                    if row['state'] == 'PENDING': row['state'] = 'UNFINISHED'
                for attempt in attempts.values(): attempt['state'] = 'UNFINISHED'
                save()
                raise
            exit_observed = False
            try:
                while True:
                    for i in indices:
                        receipt = folder/('%04d.json' % i)
                        if not receipt.exists(): continue
                        status = json.loads(receipt.read_text())
                        if status['state'] == 'STARTED':
                            if active != i: active, active_since = i, time.monotonic()
                            attempts[i]['state'] = 'STARTED'
                        elif status['state'] in ('COMPLETED', 'FAILED'):
                            if attempts[i]['state'] not in ('COMPLETED', 'FAILED'):
                                if status['state'] == 'COMPLETED':
                                    if phase=='analyze':
                                        capsule=Path(attempts[i]['output'])/'verification-capsule.json'
                                        # Structural completion only; independent replay is separate.
                                        data=json.loads(capsule.read_text())
                                        if not isinstance(data.get('decisions'),list):raise ValueError('completed receipt without a capsule decision list')
                                    if lease_fd is not None:checked_artifacts(attempts[i]['output'],status,phase)
                                attempts[i].update(status)
                                rows[i]['state'] = status['state']
                                if status['state'] == 'COMPLETED': rows[i]['output'] = attempts[i]['output']
                                save()
                                idle_since = time.monotonic()
                            if active == i: active, active_since = None, None
                        else: raise ValueError('unknown worker receipt')
                    if proc.poll() is not None:
                        # Exit can race a final receipt between the preceding read and poll.
                        if not exit_observed:
                            exit_observed = True
                            continue
                        break
                    timer = active_since if active_since is not None else idle_since
                    if time.monotonic() - timer > job_timeout:
                        reason = 'JOB_TIMEOUT' if active is not None else 'WORKER_STARTUP_TIMEOUT'
                        terminate(proc)
                        # Read the last atomic receipts before classifying unfinished work.
                        continue
                    time.sleep(0.1)
            except BaseException:
                terminate(proc)
                worker.update(exit=proc.returncode, reason='SUPERVISOR_INTERRUPTED')
                for row in rows:
                    if row['state'] == 'PENDING': row['state'] = 'UNFINISHED'
                for attempt in attempts.values():
                    if attempt['state'] not in ('COMPLETED', 'FAILED'): attempt['state'] = 'UNFINISHED'
                save()
                raise
        worker.update(state='TERMINAL',exit=proc.returncode, seconds=round(time.monotonic()-started, 3), reason=reason)
        memory = folder/'memory.txt'
        if memory.exists():
            worker['peakRssKiB'] = next((int(x.split(':')[-1]) for x in memory.read_text().splitlines()
                                        if 'Maximum resident set size' in x), None)
        unfinished = [i for i in indices if rows[i]['state'] == 'PENDING']
        charged = active if active in unfinished else (unfinished[0] if unfinished and proc.returncode != 0 else None)
        for i in unfinished:
            attempt = attempts[i]
            if i == charged or attempt['state'] == 'STARTED':
                attempt.update(state='UNFINISHED', reason=reason or 'WORKER_EXIT')
                failures = sum(a['state'] == 'UNFINISHED' for a in rows[i]['attempts'])
                rows[i]['state'] = 'PENDING' if failures <= worker_retries else 'UNFINISHED'
            else: attempt['state'] = 'NOT_STARTED'
        # A normal exit without receipts must also make finite progress.
        retired_after_failure = any(attempts[i]['state'] == 'FAILED' for i in indices)
        if unfinished and charged is None and not retired_after_failure and all(attempts[i]['state'] == 'NOT_STARTED' for i in unfinished):
            i = unfinished[0]
            attempts[i].update(state='UNFINISHED', reason='MISSING_RECEIPT')
            failures = sum(a['state'] == 'UNFINISHED' for a in rows[i]['attempts'])
            rows[i]['state'] = 'PENDING' if failures <= worker_retries else 'UNFINISHED'
        save()
    summary['completed'] = all(row['state'] == 'COMPLETED' for row in rows)
    save()
    return summary


def reconcile(jobs, output, phase, worker_retries=1):
    """Recover terminal receipts only after the inherited process lease is free."""
    path=output/'summary.json'
    if not path.exists():return None
    summary=json.loads(path.read_text())
    if summary.get('schemaVersion')!=2 or summary.get('phase')!=phase or [r['id'] for r in summary['jobs']]!=[j['id'] for j in jobs]:
        raise ValueError('incompatible recovery summary')
    for worker in summary['workers']:
        folder=output/worker['directory'];schedule=folder/'schedule.json'
        if digest(schedule)!=worker['scheduleSha256']:raise ValueError('worker schedule changed')
        for i,item in zip(worker['jobs'],json.loads(schedule.read_text()),strict=True):
            row=summary['jobs'][i]
            attempt=next(a for a in row['attempts'] if a['worker']==folder.name)
            if item['output']!=attempt['output'] or item['arguments']!=invocation(jobs[i],item['output'],phase):raise ValueError('recovery schedule mismatch')
            receipt=folder/('%04d.json'%i)
            status=json.loads(receipt.read_text()) if receipt.exists() else {}
            if attempt['state']=='COMPLETED' and status.get('state')!='COMPLETED':
                raise ValueError('completed attempt lost its durable terminal receipt')
            if status.get('state')=='COMPLETED':
                checked_artifacts(attempt['output'],status,phase)
                attempt.update(status);row.update(state='COMPLETED',output=attempt['output'])
            elif status.get('state')=='FAILED':
                attempt.update(status);row['state']='FAILED'
            elif attempt['state'] not in ('COMPLETED','FAILED','NOT_STARTED','UNFINISHED'):
                if status.get('state')=='STARTED' or attempt['state']=='STARTED':
                    attempt.update(state='UNFINISHED',reason='HARD_INTERRUPTION',timing='CENSORED')
                    row['state']='UNFINISHED'
                else:
                    attempt.update(state='NOT_STARTED',reason='HARD_INTERRUPTION',timing='CENSORED')
        if worker.get('state')!='TERMINAL':worker.update(state='INTERRUPTED',reason='HARD_INTERRUPTION',timing='CENSORED')
    for row in summary['jobs']:
        if row['state'] in ('PENDING','UNFINISHED'):
            failures=sum(a['state']=='UNFINISHED' for a in row['attempts'])
            row['state']='PENDING' if failures<=worker_retries else 'UNFINISHED'
    summary['completed']=all(r['state']=='COMPLETED' for r in summary['jobs'])
    atomic_json(path,summary)
    return summary


def observations(summary):
    return {r['id']:dict(path=str(Path(r['output'])/'observation.xmi'),
        sha256=digest(Path(r['output'])/'observation.xmi')) for r in summary['jobs'] if r['state']=='COMPLETED'}


def main():
    entered=time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jobs', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jobs-per-worker', type=int, default=4)
    parser.add_argument('--job-timeout', type=float, default=120)
    parser.add_argument('--worker-retries', type=int, default=1)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--heap', default='1g')
    parser.add_argument('--gc-log', action='store_true', help='opt-in diagnostic GC logging')
    parser.add_argument('--resumable',action='store_true',help='prepare input observations for hard-loss recovery')
    parser.add_argument('--resume',action='store_true',help='reconcile an existing resumable batch')
    args = parser.parse_args()
    if os.name != 'posix': parser.error('Linux process groups are required')
    if args.jobs_per_worker < 1 or args.worker_retries < 0 or not math.isfinite(args.job_timeout) or args.job_timeout <= 0:
        parser.error('invalid operational budgets')
    # Heap is a VM option value, never an additional command or semantic threshold.
    import re
    if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]?', args.heap): parser.error('invalid heap value')
    jobs = load_jobs(args.jobs.resolve())
    original_jar = args.jar.resolve(strict=True)
    java, javac = shutil.which(args.java), shutil.which(args.javac)
    if not java or not javac: parser.error('java and javac must be available')
    if not Path('/usr/bin/time').is_file(): parser.error('/usr/bin/time is required')
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
    compiler = subprocess.run([javac, '-version'], capture_output=True, text=True, check=True)
    if not re.search(r'version "17[.\"]', version.stderr + version.stdout) or not re.search(r'javac 17[. ]', compiler.stdout + compiler.stderr):
        parser.error('this validated runner requires JDK17 for both java and javac')
    output = args.output.resolve()
    if any(output.is_relative_to(Path(job['source'])) for job in jobs):
        parser.error('output must be outside every analyzed source tree')
    if not args.resume:durable_mkdir(output)
    with batch_lease(output) as lease_fd:
        return execute_batch(args,jobs,original_jar,java,javac,version,compiler,output,lease_fd,entered)


def execute_batch(args,jobs,original_jar,java,javac,version,compiler,output,lease_fd,entered):
    jar = output/'engine.jar'
    helper=Path(__file__).with_name('batch')/'SequentialAnalysisWorker.java'
    jar_hash=digest(original_jar)
    config=dict(schemaVersion=2, jobs=jobs, jar=str(jar), originalJar=str(original_jar),
        jarSha256=jar_hash, java=java, javac=javac,
        javaSha256=digest(Path(java).resolve()),javacSha256=digest(Path(javac).resolve()),helperSha256=digest(helper),
        javaVersion=version.stdout + version.stderr, javacVersion=compiler.stdout + compiler.stderr,
        jobsPerWorker=args.jobs_per_worker, jobTimeout=args.job_timeout, workerRetries=args.worker_retries,
        heap=args.heap, gcLogging=args.gc_log,resumable=args.resumable or args.resume,independentReplay='NOT_REQUESTED')
    if args.resume:
        if not (output/'inputs.json').exists():raise ValueError('no durable recovery configuration; start a new batch')
        frozen=json.loads((output/'inputs.json').read_text())
        if not frozen.get('resumable') or frozen!=config:raise ValueError('resume requires exact resumable engine, worker, jobs and configuration')
        if digest(jar)!=jar_hash:raise ValueError('frozen engine changed')
    else:
        shutil.copyfile(original_jar,jar)
        with jar.open('rb') as stream:os.fsync(stream.fileno())
        if digest(jar)!=jar_hash:raise ValueError('engine changed while snapshotting')
        atomic_json(output/'inputs.json',config)
    ledger_path=output/'lifecycle.json'
    ledger=json.loads(ledger_path.read_text()) if ledger_path.exists() else dict(schemaVersion=1,sessions=[])
    for old in ledger['sessions']:
        if old['state']=='RUNNING':old.update(state='INTERRUPTED',timing='CENSORED',unmeasuredInterval='supervisor/host unavailable')
    session=dict(mode='resume' if args.resume else 'initial',state='RUNNING',startedUnix=time.time(),stages=[])
    ledger['sessions'].append(session);atomic_json(ledger_path,ledger)
    try:return execute_phases(args,jobs,java,javac,output,jar,helper,lease_fd,ledger,session,entered)
    except BaseException as error:
        session.update(state='FAILED',error=type(error).__name__+': '+str(error));raise
    finally:
        session['seconds']=time.monotonic()-entered;atomic_json(ledger_path,ledger)


def execute_phases(args,jobs,java,javac,output,jar,helper,lease_fd,ledger,session,entered):
    @contextmanager
    def measured(name,started=None):
        started=time.monotonic() if started is None else started
        record=dict(name=name,state='COMPLETED')
        try:yield record
        except BaseException as error:
            record.update(state='FAILED',error=type(error).__name__+': '+str(error))
            session['state']='FAILED'
            raise
        finally:
            record['seconds']=time.monotonic()-started
            session['stages'].append(record)
            atomic_json(output/'lifecycle.json',ledger)
    classes = output/'classes'
    try:
        with measured('configuration-and-compilation',entered):
            subprocess.run([javac, '-cp', str(jar), '-d', str(classes),
                            str(helper)], check=True)
    except subprocess.CalledProcessError:
        # Preserve existing attempts and avoid creating an incompatible summary
        # before the first worker can run. A later resume can recompile safely.
        atomic_json(output/'startup-error.json',dict(error='WORKER_COMPILATION_FAILED'))
        return 1
    def command(schedule, folder):
        diagnostics = ['-Xlog:gc*:file=' + str(folder/'gc.log')] if args.gc_log else []
        return ['/usr/bin/time', '-v', '-o', str(folder/'memory.txt'), java, '-Xmx' + args.heap,
                '-Dmetamodel.conformance.alloy.processes=1', *diagnostics, '-cp', str(classes) + os.pathsep + str(jar),
                'metamodel.conformance.pipeline.cli.SequentialAnalysisWorker', str(schedule)]
    expected=None
    if args.resumable or args.resume:
        with measured('input-preparation-and-revalidation'):
            prep=output/'preparation';durable_mkdir(prep,exist_ok=True)
            previous=reconcile(jobs,prep,'observe',args.worker_retries) if args.resume else None
            if previous:
                existing=observations(previous)
                if existing:
                    check=output/('revalidation-%04d'%len(ledger['sessions']));durable_mkdir(check)
                    selected=[j for j in jobs if j['id'] in existing]
                    validation=run_jobs(selected,check,command,args.jobs_per_worker,args.job_timeout,args.worker_retries,
                        phase='observe',expected_observations=existing,lease_fd=lease_fd)
                    if not validation['completed']:raise ValueError('input revalidation failed; completed outputs cannot be reused')
            preparation=run_jobs(jobs,prep,command,args.jobs_per_worker,args.job_timeout,args.worker_retries,
                phase='observe',initial_summary=previous,lease_fd=lease_fd)
            if not preparation['completed']:raise ValueError('input preparation incomplete')
            expected=observations(preparation)
    with measured('analysis-and-reconciliation') as cost:
        summary = run_jobs(jobs, output, command, args.jobs_per_worker, args.job_timeout, args.worker_retries,
            expected_observations=expected,initial_summary=reconcile(jobs,output,'analyze',args.worker_retries) if args.resume else None,lease_fd=lease_fd)
        if not summary['completed']:cost['state']='FAILED'
    session['state']='COMPLETED' if summary['completed'] else 'FAILED'
    print(json.dumps(dict(completed=summary['completed'], states={r['id']: r['state'] for r in summary['jobs']})))
    return 0 if summary['completed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
