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


def atomic_json(path, data):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, indent=2))
    temporary.replace(path)


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


def invocation(job, output):
    values = ['analyze', '--source', job['source'], '--language', job['language'], '--output', str(output)]
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


def run_jobs(jobs, output, command_factory, jobs_per_worker, job_timeout, worker_retries):
    """command_factory is a testable process boundary; the CLI uses only the Java worker."""
    rows = [dict(id=j['id'], state='PENDING', attempts=[]) for j in jobs]
    summary = dict(schemaVersion=1, jobs=rows, workers=[])
    def save(): atomic_json(output/'summary.json', summary)
    save()
    while any(row['state'] == 'PENDING' for row in rows):
        indices = [i for i, row in enumerate(rows) if row['state'] == 'PENDING'][:jobs_per_worker]
        folder = output/('worker-%04d' % len(summary['workers']))
        folder.mkdir()
        schedule, attempts = [], {}
        for i in indices:
            attempt = dict(state='SCHEDULED', worker=folder.name)
            rows[i]['attempts'].append(attempt)
            directory = output/'jobs'/('%04d' % i)/('attempt-%04d' % len(rows[i]['attempts']))
            directory.mkdir(parents=True)
            attempt['output'] = str(directory/'result')
            attempts[i] = attempt
            schedule.append(dict(receipt=str(folder/('%04d.json' % i)), output=attempt['output'],
                                 arguments=invocation(jobs[i], attempt['output'])))
        schedule_path = folder/'schedule.json'
        atomic_json(schedule_path, schedule)
        worker = dict(directory=folder.name, jobs=indices)
        summary['workers'].append(worker)
        save()
        started, active, active_since, reason = time.monotonic(), None, None, None
        idle_since = started
        with (folder/'worker.log').open('w') as log:
            try:
                proc = subprocess.Popen(command_factory(schedule_path, folder), stdout=log,
                                        stderr=subprocess.STDOUT, start_new_session=True)
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
                                    capsule = Path(attempts[i]['output'])/'verification-capsule.json'
                                    # Structural completion only; independent replay remains a separate gate.
                                    data = json.loads(capsule.read_text())
                                    if not isinstance(data.get('decisions'), list):
                                        raise ValueError('completed receipt without a capsule decision list')
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
        worker.update(exit=proc.returncode, seconds=round(time.monotonic()-started, 3), reason=reason)
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


def main():
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
    output.mkdir(parents=True, exist_ok=False)
    jar = output/'engine.jar'
    shutil.copyfile(original_jar, jar)
    def digest(path):
        value = hashlib.sha256()
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''): value.update(chunk)
        return value.hexdigest()
    jar_hash = digest(jar)
    if jar_hash != digest(original_jar): raise ValueError('engine changed while snapshotting')
    atomic_json(output/'inputs.json', dict(schemaVersion=1, jobs=jobs, jar=str(jar), originalJar=str(original_jar),
        jarSha256=jar_hash, java=java, javac=javac,
        javaVersion=version.stdout + version.stderr, javacVersion=compiler.stdout + compiler.stderr,
        jobsPerWorker=args.jobs_per_worker, jobTimeout=args.job_timeout, workerRetries=args.worker_retries,
        heap=args.heap, gcLogging=args.gc_log, independentReplay='NOT_REQUESTED'))
    classes = output/'classes'
    try:
        subprocess.run([javac, '-cp', str(jar), '-d', str(classes),
                        str(Path(__file__).with_name('batch')/'SequentialAnalysisWorker.java')], check=True)
    except subprocess.CalledProcessError:
        atomic_json(output/'summary.json', dict(schemaVersion=1, completed=False, workers=[],
            startupError='WORKER_COMPILATION_FAILED',
            jobs=[dict(id=j['id'],state='UNFINISHED',attempts=[]) for j in jobs]))
        return 1
    def command(schedule, folder):
        diagnostics = ['-Xlog:gc*:file=' + str(folder/'gc.log')] if args.gc_log else []
        return ['/usr/bin/time', '-v', '-o', str(folder/'memory.txt'), java, '-Xmx' + args.heap,
                '-Dmetamodel.conformance.alloy.processes=1', *diagnostics, '-cp', str(classes) + os.pathsep + str(jar),
                'metamodel.conformance.pipeline.cli.SequentialAnalysisWorker', str(schedule)]
    summary = run_jobs(jobs, output, command, args.jobs_per_worker, args.job_timeout, args.worker_retries)
    print(json.dumps(dict(completed=summary['completed'], states={r['id']: r['state'] for r in summary['jobs']})))
    return 0 if summary['completed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
