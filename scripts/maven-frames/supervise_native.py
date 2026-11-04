"""Resumable Linux supervisor for native capture, using the existing inherited lease.

No in-place build restart and no formal verdict cache. Only verified terminal
source-observation bundles can be reused; unfinished work retries in fresh trees.
"""
from pathlib import Path,PurePosixPath
import argparse,json,os,re,signal,subprocess,sys,time
from capture_native import HERE,module,location,checked_profile,tree_inventory,arguments
from native_context_bundle import digest,regular_root,verify_bundle,FrameAdmissionError
from frame_receipt import read_frames
from retained_inputs import _file,_metadata,_relative


def read_json(path):
 if path.is_symlink() or not path.is_file() or path.stat().st_size>64*1024*1024:raise FrameAdmissionError('regular bounded recovery record required')
 def unique(pairs):
  value={}
  for k,v in pairs:
   if k in value:raise FrameAdmissionError('duplicate recovery field')
   value[k]=v
  return value
 return json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique)


def clients(name):
 found=[]
 for entry in Path('/proc').iterdir():
  if not entry.name.isdigit():continue
  try:argv=(entry/'cmdline').read_bytes().split(b'\0')
  except (FileNotFoundError,ProcessLookupError,PermissionError):continue
  if argv and Path(argv[0].decode(errors='replace')).name=='docker' and len(argv)>1 and argv[1]==b'run' and name.encode() in argv:found.append(entry.name)
 return found


def discover_owned(attempt,image):
 """Recover missing launch metadata from the durably declared private namespace."""
 result=subprocess.run(['docker','container','ls','--all','--filter','ancestor='+image,'--format','{{.ID}}'],capture_output=True,text=True,timeout=10)
 if result.returncode!=0:raise FrameAdmissionError('private native namespace discovery unavailable')
 ids=result.stdout.split()
 if len(ids)>128:raise FrameAdmissionError('native namespace discovery exceeds128-container policy')
 names=set()
 for identifier in ids:
  query=subprocess.run(['docker','inspect',identifier],capture_output=True,text=True,timeout=10)
  if query.returncode!=0:raise FrameAdmissionError('candidate container changed during ownership discovery')
  rows=json.loads(query.stdout)
  if not isinstance(rows,list) or len(rows)!=1:raise FrameAdmissionError('ambiguous native namespace candidate')
  data=rows[0];mounts={m['Destination']:m['Source'] for m in data.get('Mounts',[]) if m.get('Type')=='bind'}
  if data.get('Image')==image and mounts.get('/workspace/source')==str(attempt/'project') and mounts.get('/output')==str(attempt/'native-output'):
   name=data.get('Name','').removeprefix('/')
   if not re.fullmatch('conformance-provider-[0-9a-f]{32}',name):raise FrameAdmissionError('unrecognized resource occupies the private native namespace')
   names.add(name)
 # A Docker create RPC can still be in flight. Discover its owned name from a
 # live client command without killing or trusting a persisted PID.
 expected=[('type=bind,src='+str(attempt/'project')+',dst=/workspace/source').encode(),('type=bind,src='+str(attempt/'native-output')+',dst=/output').encode()]
 for entry in Path('/proc').iterdir():
  if not entry.name.isdigit():continue
  try:argv=(entry/'cmdline').read_bytes().split(b'\0')
  except (FileNotFoundError,ProcessLookupError,PermissionError):continue
  if argv and Path(argv[0].decode(errors='replace')).name=='docker' and len(argv)>1 and argv[1]==b'run' and all(value in argv for value in expected) and b'--name' in argv:
   index=argv.index(b'--name')
   if len(argv)>index+2 and argv[index+2].decode()==image:
    name=argv[index+1].decode()
    if not re.fullmatch('conformance-provider-[0-9a-f]{32}',name):raise FrameAdmissionError('unrecognized client occupies private native namespace')
    names.add(name)
 return sorted(names)


def cleanup_owned(attempt,image,execution):
 """Caller has acquired the inherited recovery lease; no persisted PID is killed."""
 receipt=attempt/'attempt.json'
 if receipt.is_symlink():raise FrameAdmissionError('native attempt receipt became a symlink')
 receipt_error=None
 try:
  data=read_json(receipt) if receipt.exists() else {}
  if not isinstance(data,dict):raise FrameAdmissionError('native attempt receipt must be an object')
  if 'nativeCommand' in data and (not isinstance(data.get('ownedContainer'),str) or not re.fullmatch('conformance-provider-[0-9a-f]{32}',data['ownedContainer']) or data.get('image')!=image):raise FrameAdmissionError('registered native ownership unavailable')
 except (OSError,ValueError) as error:
  # Never repair or trust corrupt launch metadata. Independent private-mount
  # discovery can still prove ownership and clean resources before rejection.
  data={};receipt_error=repr(error)
 if 'nativeCommand' not in data:
  names=discover_owned(attempt,image)
  result=dict(status='ABSENT',discovery='PRIVATE_MOUNT_NAMESPACE',resources=[cleanup_name(attempt,image,execution,name) for name in names])
  if receipt_error is not None:result['receiptError']=receipt_error
  return result
 name=data.get('ownedContainer')
 return cleanup_name(attempt,image,execution,name)


def cleanup_name(attempt,image,execution,name):
 deadline=time.monotonic()+20
 while True:
  query=subprocess.run(['docker','inspect',name],capture_output=True,text=True,timeout=10)
  if query.returncode==0:
   rows=json.loads(query.stdout)
   if not isinstance(rows,list) or len(rows)!=1:raise FrameAdmissionError('ambiguous owned container inspect')
   observed=rows[0];mounts={m['Destination']:m['Source'] for m in observed.get('Mounts',[]) if m.get('Type')=='bind'}
   if observed.get('Name')!='/'+name or observed.get('Image')!=image or mounts.get('/workspace/source')!=str(attempt/'project') or mounts.get('/output')!=str(attempt/'native-output'):raise FrameAdmissionError('container is not the registered private native attempt')
   removed=execution._remove_container(name,15)
   if removed['status']!='ABSENT':raise FrameAdmissionError('owned orphan cleanup unconfirmed')
   while clients(name) and time.monotonic()<deadline:time.sleep(.05)
   if clients(name):raise FrameAdmissionError('owned Docker client still active after cleanup')
   return removed
  absence=subprocess.run(['docker','container','ls','--all','--filter','name=^/'+name+'$','--format','{{.ID}}'],capture_output=True,text=True,timeout=10)
  if absence.returncode!=0 or absence.stdout.strip():raise FrameAdmissionError('owned container absence unconfirmed')
  if not clients(name):return dict(status='ABSENT',name=name)
  if time.monotonic()>=deadline:raise FrameAdmissionError('owned Docker client remains active without an inspectable container')
  time.sleep(.05)


def check_live_cache(bundle,cache):
 """Reject changed selected external resolution inputs, not unrelated cache files."""
 for path in (bundle/'inputs').glob('*.inputs/manifest.jsonl'):
  rows=read_frames(path)
  if len(rows)!=1:raise FrameAdmissionError('one retained input manifest required')
  for role in ('classpath','processorPath'):
   for entry in rows[0].get(role) or []:
    original=PurePosixPath(entry['original'])
    try:relative=original.relative_to('/workspace/artifact-cache')
    except ValueError:continue # Private native outputs are temporal snapshots.
    _relative(relative.as_posix(),dot=True);live=cache/'repository'/relative.as_posix()
    if any(p.is_symlink() for p in [live,*live.parents]):raise FrameAdmissionError('selected cache input became a symlink')
    if entry['kind']=='ABSENT':
     if live.exists():raise FrameAdmissionError('previously absent external input appeared')
     continue
    expected=set()
    for node in entry['inventory']:
     name=_relative(node['path'],dot=True);p=live if name=='.' else live/name;expected.add(name)
     if node['kind']=='FILE':_file(p,node)
     elif node['kind']=='DIRECTORY' and p.is_dir() and not p.is_symlink():_metadata(p,node)
     else:raise FrameAdmissionError('selected external input kind changed')
    actual={'.'}
    if live.is_dir():
     for p in live.rglob('*'):
      if p.is_symlink() or len(actual)>=32768:raise FrameAdmissionError('selected external input tree policy exceeded')
      actual.add(p.relative_to(live).as_posix())
    if actual!=expected:raise FrameAdmissionError('selected external input inventory changed')


def implementation():
 names=['capture_native.py','native_context_bundle.py','compiler_receipt.py','frame_receipt.py','parser_receipt.py','retained_inputs.py','supervise_native.py','CompilerFrames.java','CompilerBoundary.java','SourceMonitor.java','RetainedInputs.java','NativeSourceTrace.java','../provider-execution.py','../run-analysis-batch.py','../maven-cache/cache-profile.py','../maven-cache/OriginFactory.java']
 return {n:digest(HERE/n) for n in names}


def checked_state(state,output,retries):
 if not isinstance(state,dict) or state.get('format')!='native-capture-supervisor-summary-v1' or not isinstance(state.get('attempts'),list) or not isinstance(state.get('sessions'),list) or len(state['attempts'])>retries+1:raise FrameAdmissionError('invalid native recovery summary')
 for i,row in enumerate(state['attempts'],1):
  if not isinstance(row,dict) or row.get('directory')!='attempt-'+str(i).zfill(4) or row.get('status') not in ('RUNNING','UNFINISHED','FAILED','BUNDLE_READY'):raise FrameAdmissionError('native attempt history/census mismatch')
 declared={row['directory'] for row in state['attempts']}
 if any(p.name not in declared for p in output.iterdir() if re.fullmatch('attempt-[0-9]{4}',p.name)):raise FrameAdmissionError('unrecorded native attempt cannot be dropped from retries')
 return state


def revalidate(frozen,original,source,profile,cache,cache_api):
 if implementation()!=frozen['implementation'] or digest(Path(sys.executable).resolve())!=frozen['pythonSha256'] or digest(profile/'profile.json')!=frozen['collectorProfileSha256'] or digest(cache/'profile.json')!=frozen['cacheProfileSha256'] or digest(cache/'extension.jar')!=frozen['cacheExtensionSha256'] or tree_inventory(source)!=original:raise FrameAdmissionError('native inputs/profile/implementation changed during supervision')
 checked_profile(profile);cache_api.validate(cache,json.loads((profile/'profile.json').read_text())['requestedImage'])


def run(source,profile,cache,output,maven_args,native_timeout,attempt_timeout,retries,resume):
 entered=time.monotonic();source=regular_root(location(source));profile=regular_root(location(profile));cache=regular_root(location(cache));output=location(output);arguments(maven_args)
 if type(retries) is not int or not 0<=retries<=10 or not 0<native_timeout<=86400 or not native_timeout<attempt_timeout<=172800:raise FrameAdmissionError('finite retry/native/attempt budgets required')
 for a,b in ((source,profile),(source,cache),(profile,cache),(output,source),(output,profile),(output,cache)):
  if a.is_relative_to(b) or b.is_relative_to(a):raise FrameAdmissionError('supervisor/source/profile/cache roots must be independent')
 durable=module('native_supervisor_durability',HERE.parent/'run-analysis-batch.py');execution=module('native_supervisor_execution',HERE.parent/'provider-execution.py');cache_api=module('native_supervisor_cache',HERE.parent/'maven-cache/cache-profile.py')
 if not resume:durable.durable_mkdir(output)
 else:regular_root(output)
 with durable.batch_lease(output) as lease:
  config=checked_profile(profile);cache_api.validate(cache,config['requestedImage'])
  image=subprocess.run(['docker','image','inspect',config['requestedImage'],'--format','{{.Id}}'],capture_output=True,text=True,timeout=20)
  if image.returncode or image.stdout.strip()!=config['image']:raise FrameAdmissionError('native image unavailable or changed')
  frozen=dict(format='native-capture-supervisor-v1',source=str(source),profile=str(profile),cache=str(cache),arguments=maven_args,nativeTimeout=native_timeout,attemptTimeout=attempt_timeout,retries=retries,implementation=implementation(),collectorProfileSha256=digest(profile/'profile.json'),cacheProfileSha256=digest(cache/'profile.json'),cacheExtensionSha256=digest(cache/'extension.jar'),image=config['image'],python=str(Path(sys.executable).resolve()),pythonSha256=digest(Path(sys.executable).resolve()))
  original=tree_inventory(source)
  if resume:
   if read_json(output/'config.json')!=frozen or read_json(output/'input-tree.json')!=original:raise FrameAdmissionError('recovery inputs/profile/implementation/configuration changed')
   state=checked_state(read_json(output/'summary.json'),output,retries)
  else:
   durable.atomic_json(output/'config.json',frozen);durable.atomic_json(output/'input-tree.json',original)
   state=dict(format='native-capture-supervisor-summary-v1',status='RUNNING',attempts=[],sessions=[],consumptionCertified=False,canonicalAdmissionCertified=False)
  session=dict(mode='resume' if resume else 'initial',status='RUNNING',startedUnix=time.time());state['sessions'].append(session)
  def save():session['lastRecordedSeconds']=time.monotonic()-entered;durable.atomic_json(output/'summary.json',state)
  def accepted(row):
   revalidate(frozen,original,source,profile,cache,cache_api)
   attempt=output/row['directory'];receipt=attempt/'attempt.json'
   if receipt.is_symlink():raise FrameAdmissionError('native attempt receipt became a symlink')
   if not receipt.exists():
    if row.get('receiptSha256') is not None or row['status']=='BUNDLE_READY':raise FrameAdmissionError('terminal native receipt disappeared')
    return False
   data=read_json(receipt)
   if row.get('receiptSha256') is not None and row['receiptSha256']!=digest(attempt/'attempt.json'):raise FrameAdmissionError('terminal native receipt changed')
   if data.get('status')!='BUNDLE_READY' or data.get('cleanup',{}).get('status')!='ABSENT' or data.get('bundle')!=str(attempt/'bundle'):return False
   if read_json(attempt/'input-tree.json')!=original:raise FrameAdmissionError('native worker source snapshot differs from frozen input')
   binding=verify_bundle(attempt/'bundle',data['bindingSha256']);check_live_cache(attempt/'bundle',cache)
   if binding['image']!=config['image']:raise FrameAdmissionError('native bundle image differs from frozen profile')
   row.update(status='BUNDLE_READY',receiptSha256=digest(attempt/'attempt.json'),bindingSha256=data['bindingSha256']);state.update(status='BUNDLE_READY',bundle=str(attempt/'bundle'),bindingSha256=data['bindingSha256']);return True
  old_handlers={s:signal.getsignal(s) for s in (signal.SIGTERM,signal.SIGINT)};worker=None
  def interrupt(signum,frame):raise InterruptedError('native supervisor interrupted by signal '+str(signum))
  for s in old_handlers:signal.signal(s,interrupt)
  save()
  try:
   for row in state['attempts']:
    if not isinstance(row.get('directory'),str) or not re.fullmatch('attempt-[0-9]{4}',row['directory']):raise FrameAdmissionError('invalid native attempt directory')
    row['recoveryCleanup']=cleanup_owned(output/row['directory'],config['image'],execution)
   if any('receiptError' in row['recoveryCleanup'] for row in state['attempts']):raise FrameAdmissionError('corrupt native attempt receipt; owned cleanup performed but reuse rejected')
   for row in state['attempts']:
    if accepted(row):session['status']='REUSED_VERIFIED_BUNDLE';save();return 0,state
    if row['status']=='FAILED':state['status']='FAILED';session['status']='RETAINED_FAILURE';save();return 1,state
    row['status']='UNFINISHED' if row.get('status') in ('RUNNING','UNFINISHED') else row['status']
   while len(state['attempts'])<retries+1:
    revalidate(frozen,original,source,profile,cache,cache_api)
    row=dict(directory='attempt-'+str(len(state['attempts'])+1).zfill(4),status='RUNNING');state['attempts'].append(row);state['status']='RUNNING';save();attempt=output/row['directory']
    command=[sys.executable,'-B',str(HERE/'capture_native.py'),'--source',str(source),'--collector-profile',str(profile),'--cache-profile',str(cache),'--output',str(attempt),'--maven-args-json',json.dumps(maven_args),'--native-timeout',str(native_timeout)]
    with (output/(row['directory']+'.log')).open('x') as log:
     worker=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True,pass_fds=(lease,));row['workerPid']=worker.pid;save()
     try:code=worker.wait(timeout=attempt_timeout)
     except subprocess.TimeoutExpired:
      os.killpg(worker.pid,signal.SIGTERM)
      try:worker.wait(timeout=45)
      except subprocess.TimeoutExpired:durable.terminate(worker)
      code=124
    worker=None;row['workerExit']=code;row['recoveryCleanup']=cleanup_owned(attempt,config['image'],execution)
    if accepted(row):session['status']='COMPLETED';save();return 0,state
    data=read_json(attempt/'attempt.json') if (attempt/'attempt.json').exists() else {}
    if code in (0,1,70) and data.get('status') not in ('RUNNING','INTERRUPTED','TIMED_OUT'):
     row['status']='FAILED';state['status']='FAILED';session['status']='FAILED';save();return 1,state
    row['status']='UNFINISHED';save()
   state['status']='UNFINISHED';session['status']='RETRY_BUDGET_EXHAUSTED';save();return 124,state
  except (KeyboardInterrupt,InterruptedError):
   if worker is not None and worker.poll() is None:
    os.killpg(worker.pid,signal.SIGTERM)
    try:worker.wait(timeout=45)
    except subprocess.TimeoutExpired:durable.terminate(worker)
   if state['attempts']:state['attempts'][-1]['recoveryCleanup']=cleanup_owned(output/state['attempts'][-1]['directory'],config['image'],execution);state['attempts'][-1]['status']='UNFINISHED'
   state['status']='UNFINISHED';session['status']='INTERRUPTED';save();return 130,state
  except Exception as error:
   cleanup_errors=[]
   if worker is not None:
    # A failed post-Popen durable save must not bypass the owned child's
    # deadline/cleanup. Only the live Popen handle supplies process identity.
    try:
     if worker.poll() is None:
      try:os.killpg(worker.pid,signal.SIGTERM)
      except ProcessLookupError:pass
     try:worker.wait(timeout=45)
     except subprocess.TimeoutExpired:durable.terminate(worker)
    except Exception as cleanup_error:cleanup_errors.append(repr(cleanup_error))
    try:state['attempts'][-1]['recoveryCleanup']=cleanup_owned(output/state['attempts'][-1]['directory'],config['image'],execution)
    except Exception as cleanup_error:cleanup_errors.append(repr(cleanup_error))
   state['status']='REJECTED';session.update(status='REJECTED',error=repr(error))
   if cleanup_errors:session['failureCleanupErrors']=cleanup_errors
   try:save()
   except Exception:pass # Preserve the original failure even if recording fails again.
   raise
  finally:
   for s,h in old_handlers.items():signal.signal(s,h)


def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ('source','collector-profile','cache-profile','output'):p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--maven-args-json',default='["compile"]');p.add_argument('--native-timeout',type=float,default=120);p.add_argument('--attempt-timeout',type=float,default=240);p.add_argument('--retries',type=int,default=1);p.add_argument('--resume',action='store_true');a=p.parse_args()
 try:code,state=run(a.source,a.collector_profile,a.cache_profile,a.output,json.loads(a.maven_args_json),a.native_timeout,a.attempt_timeout,a.retries,a.resume)
 except (OSError,ValueError,subprocess.SubprocessError) as error:p.exit(70,str(error)+'\n')
 print(json.dumps(dict(status=state['status'],summary=str(a.output/'summary.json'),bundle=state.get('bundle'),bindingSha256=state.get('bindingSha256'))));return code


if __name__=='__main__':raise SystemExit(main())
