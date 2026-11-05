"""Bounded opt-in native Maven capture and verified source-observation bundle.

Runs a private project copy without rewriting the POM or upgrading its compiler.
Success means the bundle is ready, never that the program conforms to P0.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,re,shutil,signal,subprocess,time,uuid
from native_context_bundle import build_bundle,verify_bundle,source_inventory,digest,regular_root,FrameAdmissionError

HERE=Path(__file__).resolve().parent


def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


def tree_inventory(root):
 root=regular_root(root);inventory={};nodes=0;total=0
 for folder,dirs,files in os.walk(root,followlinks=False):
  for name in sorted(dirs+files):
   p=Path(folder)/name;relative=p.relative_to(root).as_posix();nodes+=1
   if nodes>65536 or p.is_symlink():raise FrameAdmissionError('project tree symlink/node policy exceeded')
   if p.is_dir():inventory[relative]=dict(kind='DIRECTORY')
   elif p.is_file():
    stat=p.stat();total+=stat.st_size
    if total>512*1024*1024:raise FrameAdmissionError('project tree exceeds512MiB policy')
    inventory[relative]=dict(kind='FILE',bytes=stat.st_size,sha256=digest(p),mode=stat.st_mode&0o777,modifiedNanos=stat.st_mtime_ns)
   else:raise FrameAdmissionError('nonregular project input')
 return inventory


def copy_project(source,target):
 before=tree_inventory(source);shutil.copytree(source,target,copy_function=shutil.copy2)
 if tree_inventory(source)!=before or tree_inventory(target)!=before:raise FrameAdmissionError('project changed while taking private snapshot')
 return before


def arguments(values):
 if not isinstance(values,list) or not values or len(values)>256 or any(not isinstance(v,str) or not v or any(c in v for c in '\0\r\n\t') for v in values):raise FrameAdmissionError('bounded Maven argument array required')
 for value in values:
  if value.startswith(('-T','--threads','-Dmaven.repo.local','-Dmaven.ext.class.path','-Dconformance.')):raise FrameAdmissionError('Maven argument overrides native capture/cache ownership')
 return values


def checked_profile(profile):
 profile=regular_root(profile);p=profile/'profile.json'
 if p.is_symlink() or not p.is_file() or p.stat().st_size>1024*1024:raise FrameAdmissionError('regular bounded collector profile required')
 data=json.loads(p.read_text())
 expected={'CompilerFrames.java','CompilerBoundary.java','SourceMonitor.java','RetainedInputs.java','NativeSourceTrace.java','CompilerToolchain.java'}
 native=HERE.parents[1]/'src/main/resources/java-native'
 resources={'NativeSemanticWorld.java','NativeTaskSemanticCapture.java','ConsumedSourceFileManager.java','ReturnedBufferStore.java','RouteTrace.java'};expected|=resources
 if data.get('format')!='native-maven-compiler-metadata-profile-v1' or data.get('status')!='BUILT_METADATA_COLLECTOR_ONLY' or set(data.get('sourceHashes',{}))!=expected:raise FrameAdmissionError('qualified collector profile required')
 for name in expected:
  if digest(profile/name)!=data['sourceHashes'][name] or digest((native if name in resources else HERE)/name)!=data['sourceHashes'][name]:raise FrameAdmissionError('collector source profile mismatch')
 for name,key in [('compiler-frames.jar','collectorJarSha256'),('source-trace.jar','sourcePluginJarSha256')]:
  p=profile/name
  if p.is_symlink() or not p.is_file() or digest(p)!=data.get(key):raise FrameAdmissionError('collector artifact profile mismatch')
 if not isinstance(data.get('requestedImage'),str) or not re.fullmatch(r'[A-Za-z0-9._/:@-]+@sha256:[0-9a-f]{64}',data['requestedImage']) or not isinstance(data.get('image'),str) or not re.fullmatch('sha256:[0-9a-f]{64}',data['image']):raise FrameAdmissionError('immutable collector image profile required')
 return data


def location(path):
 if not path.is_absolute() or path!=Path(os.path.normpath(path)) or any(c in str(path) for c in ',\r\n\t\0') or any(p.is_symlink() for p in [path,*path.parents]):raise FrameAdmissionError('canonical absolute mount/output path required')
 return path


def capture(source,profile,cache,output,maven_args,seconds):
 source=regular_root(location(source));profile=regular_root(location(profile));cache=regular_root(location(cache));output=location(output);arguments(maven_args)
 if type(seconds) not in (int,float) or not 0<seconds<=86400:raise FrameAdmissionError('finite native deadline in (0,86400] required')
 for input_root in (source,profile,cache):
  if output.is_relative_to(input_root) or input_root.is_relative_to(output):raise FrameAdmissionError('attempt output overlaps inputs')
 for first,second in ((source,profile),(source,cache),(profile,cache)):
  if first.is_relative_to(second) or second.is_relative_to(first):raise FrameAdmissionError('project, collector and cache roots must be independent')
 if output.exists() or not output.parent.is_dir():raise FrameAdmissionError('fresh output with an existing regular parent required')
 execution=module('native_provider_execution',HERE.parent/'provider-execution.py');cache_api=module('native_cache_profile',HERE.parent/'maven-cache/cache-profile.py');durable=module('native_capture_durability',HERE.parent/'run-analysis-batch.py')
 durable.durable_mkdir(output);started=time.monotonic();attempt=uuid.uuid4().hex;owned='conformance-provider-'+uuid.uuid4().hex
 receipt=dict(format='native-maven-capture-attempt-v1',attempt=attempt,status='RUNNING',ownedContainer=owned,stages=[],consumptionCertified=False,canonicalAdmissionCertified=False)
 receipt_path=output/'attempt.json'
 def save():receipt['seconds']=time.monotonic()-started;durable.atomic_json(receipt_path,receipt)
 def step(name,function):
  tick=time.monotonic()
  try:return function()
  finally:receipt['stages'].append(dict(name=name,seconds=time.monotonic()-tick));save()
 save();old_handlers={s:signal.getsignal(s) for s in (signal.SIGTERM,signal.SIGINT)}
 def interrupt(signum,frame):raise InterruptedError('native capture interrupted by signal '+str(signum))
 for s in old_handlers:signal.signal(s,interrupt)
 native=None;cleanup=None;initial=None;code=70;launch_attempted=False
 try:
  config=step('collector-profile-validation',lambda:checked_profile(profile))
  def image_and_cache():
   identity=subprocess.run(['docker','image','inspect',config['requestedImage'],'--format','{{.Id}}'],capture_output=True,text=True,timeout=20)
   if identity.returncode or identity.stdout.strip()!=config['image']:raise FrameAdmissionError('locally available collector image identity mismatch')
   cache_api.validate(cache,config['requestedImage'])
   if json.loads((cache/'profile.json').read_text()).get('imageId')!=config['image']:raise FrameAdmissionError('cache image identity mismatch')
  step('image-and-cache-validation',image_and_cache)
  project=output/'project';initial=step('private-project-snapshot',lambda:copy_project(source,project));durable.atomic_json(output/'input-tree.json',initial)
  helpers=output/'helpers';helpers.mkdir()
  for name in ('compiler-frames.jar','source-trace.jar'):shutil.copy2(profile/name,helpers/name)
  shutil.copy2(cache/'extension.jar',helpers/'cache-extension.jar');shutil.copy2(cache/'profile.json',helpers/'cache-profile.json');shutil.copy2(profile/'profile.json',helpers/'collector-profile.json')
  for name,key in [('compiler-frames.jar','collectorJarSha256'),('source-trace.jar','sourcePluginJarSha256')]:
   if digest(helpers/name)!=config[key]:raise FrameAdmissionError('collector changed during snapshot')
  if digest(helpers/'cache-extension.jar')!=json.loads((helpers/'cache-profile.json').read_text())['extensionSha256']:raise FrameAdmissionError('cache extension changed during snapshot')
  before=step('native-pre-source-inventory',lambda:source_inventory(project));durable.atomic_json(output/'java-before.json',before)
  native_output=output/'native-output';native_output.mkdir()
  base=['docker','run','--rm','--network=none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev','--memory','1536m','--pids-limit','256','-w','/workspace/source','-e','HOME=/output/home','-e','MAVEN_CONFIG=/output/home/.m2','-e','MAVEN_OPTS=-Xmx512m -Djava.io.tmpdir=/tmp -Djansi.force=false -Djansi.tmpdir=/output']
  for p,target,ro in [(project,'/workspace/source',False),(native_output,'/output',False),(cache/'repository','/workspace/artifact-cache',False),(helpers/'cache-extension.jar','/workspace/cache-extension.jar',True),(helpers/'compiler-frames.jar','/workspace/frames.jar',True),(helpers/'source-trace.jar','/workspace/source-trace.jar',True)]:base+=['--mount','type=bind,src='+str(p)+',dst='+target+(',readonly' if ro else '')]
  args=['mvn','--batch-mode','--no-transfer-progress','--offline','-q','-Dmaven.repo.local=/workspace/artifact-cache','-Dmaven.ext.class.path=/workspace/cache-extension.jar:/workspace/frames.jar','-Dconformance.cache.attempt='+attempt,'-Dconformance.cache.receipt=/output/cache-selection.json','-Dconformance.frames.receipt=/output/frames.jsonl','-Dconformance.compiler.receipt=/output/compiler.jsonl','-Dconformance.compiler.attempt='+attempt,'-Dconformance.compiler.session=/output/session.jsonl','-Dconformance.compiler.sourcePlugin=/workspace/source-trace.jar','-Dconformance.compiler.sourcePluginSha256='+config['sourcePluginJarSha256'],'-Dconformance.compiler.parsedRoot=/output/parsed-sources','-Dconformance.compiler.retainInputs=true','-Daether.syncContext.named.factory=file-lock','-Daether.syncContext.named.nameMapper=file-gav']+maven_args
  receipt.update(image=config['image'],requestedImage=config['requestedImage'],nativeCommand=base+['--name',owned,config['image']]+args,inputTreeSha256=digest(output/'input-tree.json'));save()
  launch_attempted=True
  native=step('native-maven',lambda:execution.run_stage(base,config['image'],args,output/'native.log',seconds,container_name=owned));receipt['native']=native;save()
  cleanup=step('native-container-cleanup',lambda:execution._remove_container(owned,15));receipt['cleanup']=cleanup;save()
  if cleanup['status']!='ABSENT':raise FrameAdmissionError('owned native container cleanup unconfirmed')
  after=step('native-post-source-inventory',lambda:source_inventory(project))
  envelope=dict(format='native-java-execution-envelope-v1',attempt=attempt,image=config['image'],stage=native,sourceInventory=after,collectorJarSha256=config['collectorJarSha256'],sourcePluginJarSha256=config['sourcePluginJarSha256'])
  (output/'execution.jsonl').write_text(json.dumps(envelope)+'\n',encoding='utf-8');receipt['executionSha256']=digest(output/'execution.jsonl');save()
  if native['exit']!=0:
   receipt['status']='TIMED_OUT' if native.get('timedOut') else 'NATIVE_FAILED';code=124 if native.get('timedOut') else 1
  else:
   step('cache-selection-receipt',lambda:cache_api.check_receipt(native_output/'cache-selection.json',attempt))
   bundle=output/'bundle'
   binding=step('context-bundle-construction',lambda:build_bundle(envelope,project,native_output,helpers/'compiler-frames.jar',helpers/'source-trace.jar',bundle,'/workspace/native-bundle'))
   anchor=digest(bundle/'binding.json');step('anchored-bundle-verification',lambda:verify_bundle(bundle,anchor))
   if step('caller-source-revalidation',lambda:tree_inventory(source))!=initial:raise FrameAdmissionError('caller project changed during native capture')
   step('final-collector-profile-validation',lambda:checked_profile(profile));step('final-cache-profile-validation',lambda:cache_api.validate(cache,config['requestedImage']))
   receipt.update(status='BUNDLE_READY',bundle=str(bundle),bindingSha256=anchor,sourceFilesBefore=len(before),sourceFilesAfter=len(after),worlds=len(binding['worlds']),requiredImage=binding['image']);code=0
 except (KeyboardInterrupt,InterruptedError) as error:receipt.update(status='INTERRUPTED',error=repr(error));code=130
 except Exception as error:receipt.update(status='FAILED',error=repr(error));code=70
 finally:
  # Cleanup has its own finite grace interval; never turn an unconfirmed cleanup
  # into a completed provider result. Hard SIGKILL/host recovery is not certified.
  for s in old_handlers:signal.signal(s,signal.SIG_IGN)
  try:
   if cleanup is None:cleanup=step('final-container-cleanup',lambda:execution._remove_container(owned,15)) if launch_attempted else dict(status='NOT_STARTED',name=owned)
   receipt['cleanup']=cleanup
   if cleanup['status'] not in ('ABSENT','NOT_STARTED'):receipt.update(status='FAILED',cleanupUnconfirmed=True);code=71
   save()
  finally:
   for s,h in old_handlers.items():signal.signal(s,h)
 return code,receipt


def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ('source','collector-profile','cache-profile','output'):p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--maven-args-json',default='["compile"]');p.add_argument('--native-timeout',type=float,default=120);a=p.parse_args()
 try:code,receipt=capture(a.source,a.collector_profile,a.cache_profile,a.output,json.loads(a.maven_args_json),a.native_timeout)
 except (OSError,ValueError) as error:p.exit(70,str(error)+'\n')
 print(json.dumps(dict(status=receipt['status'],attemptReceipt=str(a.output/'attempt.json'),bindingSha256=receipt.get('bindingSha256'),seconds=receipt['seconds'])));return code


if __name__=='__main__':raise SystemExit(main())
