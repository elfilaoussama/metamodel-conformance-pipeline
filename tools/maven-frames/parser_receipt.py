"""Validate native PARSE-unit population bound to a sealed compiler attempt."""
from pathlib import PurePosixPath
from urllib.parse import unquote,urlsplit
import base64,re
from compiler_receipt import validate_attempt,_path,_sha
from frame_receipt import FrameAdmissionError,read_frames


def _toolchain(value,config):
 keys={'format','mode','feature','javaVersion','javaHome','runtimeHome','executable','files','completeLoadedImageCertified'}
 if not isinstance(value,dict) or set(value)!=keys or value.get('format')!='observed-javac-toolchain-v1' or value.get('completeLoadedImageCertified') is not False:raise FrameAdmissionError('observed compiler toolchain descriptor required')
 feature=value['feature'];home=value['javaHome'];version=value['javaVersion'];fork=config.get('isFork')
 if type(feature) is not int or feature not in (8,11,17,25) or not _path(home) or not isinstance(version,str) or not re.fullmatch(r'1\.8\.[0-9A-Za-z.+_-]+' if feature==8 else str(feature)+r'(?:[.+_-][0-9A-Za-z.+_-]+)?',version):raise FrameAdmissionError('unsupported actual compiler family/version')
 if type(fork) is not bool or value['mode']!=('FORKED_JAVAC' if fork else 'IN_PROCESS_JAVAC') or (not fork and feature!=17):raise FrameAdmissionError('compiler execution mode differs from native boundary')
 expected=(PurePosixPath(home)/('bin/javac' if fork else 'bin/java')).as_posix()
 if value['executable']!=expected or (fork and config.get('getExecutable')!=expected) or (not fork and config.get('getExecutable') not in (None,'')):raise FrameAdmissionError('selected compiler executable differs')
 expected_runtime=(PurePosixPath(home)/'jre').as_posix() if feature==8 else home
 if value['runtimeHome']!=expected_runtime:raise FrameAdmissionError('selected compiler runtime home differs')
 names={'bin/java','bin/javac','release','lib/tools.jar','jre/lib/rt.jar'} if feature==8 else {'bin/java','bin/javac','release','lib/modules','lib/ct.sym'}
 files=value['files']
 if not isinstance(files,dict) or set(files)!=names:raise FrameAdmissionError('selected compiler backing population differs')
 for item in files.values():
  if not isinstance(item,dict) or set(item)!={'sha256','bytes'} or not _sha(item.get('sha256')) or type(item.get('bytes')) is not int or not 0<item['bytes']<=512*1024*1024:raise FrameAdmissionError('invalid selected compiler backing identity')
 return value


def _runtime_event(header,toolchain):
 keys={'format','phase','attempt','invocation','consumptionCertified','javaVersion','javaHome','compilerTaskClass','completionStrategy','effectiveOptions'}
 if set(header)!=keys or header['javaVersion']!=toolchain['javaVersion'] or header['javaHome']!=toolchain['runtimeHome'] or header['compilerTaskClass'] not in ('com.sun.tools.javac.api.BasicJavacTask','com.sun.tools.javac.api.JavacTaskImpl'):raise FrameAdmissionError('native parser actual runtime differs from selected compiler')
 legacy=toolchain['feature']==8
 strategy='PROCESS_SHUTDOWN_WITH_OBSERVED_EXIT_REQUIRED' if legacy else 'TASK_COMPILATION_EVENT'
 if header['completionStrategy']!=strategy:raise FrameAdmissionError('native parser completion semantics differ')
 options=header['effectiveOptions']
 if legacy:
  expected={'-source','-target','-encoding','-classpath','-sourcepath','-proc:none','-proc:only','-processor','-processorpath'}
  if not isinstance(options,dict) or set(options)!=expected or any(value is not None and (not isinstance(value,str) or '\0' in value or len(value)>65536) for value in options.values()):raise FrameAdmissionError('effective legacy compiler options unavailable')
 elif options is not None:raise FrameAdmissionError('unqualified modern effective-option claim')
 return 'PROCESS_SHUTDOWN' if legacy else 'COMPILATION_END'


def validate_parser_attempt(metadata,compiler,seal,attempt,build_exit,logical_root,plugin_sha,load,retain=None):
 """load(name) returns parsed rows plus one-pass artifact identity under a trusted root."""
 result=validate_attempt(metadata,compiler,seal,attempt,build_exit)
 if not _path(logical_root) or not _sha(plugin_sha):raise FrameAdmissionError('parser root/plugin identity unavailable')
 root=PurePosixPath(logical_root);starts={};population=[];files=[]
 keys={'format','attempt','invocation','plugin','pluginSha256','output','addedCompilerArgument','processorPath','consumptionCertified'}
 for row in compiler[0]:
  if row['compilerClass']!='org.codehaus.plexus.compiler.javac.JavacCompiler':raise FrameAdmissionError('native parser compiler profile unsupported')
  invocation=row['invocation'];monitor=row.get('sourceMonitor')
  if not isinstance(monitor,dict):raise FrameAdmissionError('native parser monitoring not enabled for every compiler call')
  name='invocation-'+str(invocation)+'.jsonl';output=(root/name).as_posix()
  semantic=monitor.get('format')=='native-javac-source-monitor-v4'
  runtime=semantic or monitor.get('format')=='native-javac-source-monitor-v3'
  retained=runtime or monitor.get('format')=='native-javac-source-monitor-v2';current_keys=keys|({'retainedInputs'} if retained else set())|({'toolchain'} if runtime else set())|({'semanticOutput'} if semantic else set())
  token=base64.urlsafe_b64encode(output.encode()).decode()
  retained_name='invocation-'+str(invocation)+'.inputs';retained_root=(root/retained_name).as_posix()
  extra=' '+base64.urlsafe_b64encode(retained_root.encode()).decode() if retained else ''
  if runtime:extra+=' runtime-lifecycle-v1'
  if semantic:
   semantic_name='invocation-'+str(invocation)+'.semantic.jsonl';semantic_output=(root/semantic_name).as_posix()
   semantic_token=base64.urlsafe_b64encode(semantic_output.encode()).decode()
   extra+=' '+semantic_token
   if monitor.get('semanticOutput')!=semantic_output:raise FrameAdmissionError('native semantic instrumentation binding mismatch')
  if monitor.get('format') not in ('native-javac-source-monitor-v1','native-javac-source-monitor-v2','native-javac-source-monitor-v3','native-javac-source-monitor-v4') or monitor.get('attempt')!=attempt or type(monitor.get('invocation')) is not int or monitor['invocation']!=invocation or monitor.get('pluginSha256')!=plugin_sha or not _path(monitor.get('plugin')) or monitor.get('consumptionCertified') is not False or monitor.get('output')!=output or monitor.get('addedCompilerArgument')!='-Xplugin:ConformanceSourceTrace '+token+' '+attempt+' '+str(invocation)+extra:raise FrameAdmissionError('native parser instrumentation binding mismatch')
  if retained and (not isinstance(monitor.get('retainedInputs'),dict) or monitor['retainedInputs'].get('root')!=retained_root or retain is None):raise FrameAdmissionError('retained input verification required')
  config=row['configuration'];original=config.get('getProcessorPathEntries')
  toolchain=_toolchain(monitor.get('toolchain'),config) if runtime else None
  if original is not None and (not isinstance(original,list) or any(not _path(p) for p in original)):raise FrameAdmissionError('native processor search path unavailable')
  if monitor.get('processorPath')!=(config['getClasspathEntries'] if original is None else original)+[monitor['plugin']]:raise FrameAdmissionError('processor search order was not preserved')
  if row['phase']=='START':
   if set(monitor)!=current_keys:raise FrameAdmissionError('unexpected parser start fields')
   starts[invocation]=monitor;continue
  if set(monitor)!=current_keys|{'artifact'}|({'semanticArtifact'} if semantic else set()) or {key:monitor[key] for key in current_keys}!=starts[invocation]:raise FrameAdmissionError('parser instrumentation changed during compilation')
  identity=monitor['artifact']
  if identity is None:
   if semantic and monitor['semanticArtifact'] is not None:raise FrameAdmissionError('semantic receipt without native parser task')
   if config['getSourceFiles']:raise FrameAdmissionError('selected source compilation lacks native parser task')
   item=dict(frame=row['frame'],invocation=invocation,state='NO_JAVAC_TASK',units=[])
   if retained:item['retainedInputs']=retain(retained_name,monitor['retainedInputs'],[],config);files.append(retained_name)
   population.append(item);continue
  if not isinstance(identity,dict) or set(identity)!={'sha256','bytes'} or not _sha(identity.get('sha256')) or type(identity.get('bytes')) is not int or identity['bytes']<0:raise FrameAdmissionError('invalid parser artifact identity')
  events,actual=load(name)
  if actual!=identity:raise FrameAdmissionError('native parser artifact bytes changed')
  files.append(name)
  event_format='native-javac-source-trace-v3' if runtime else 'native-javac-source-trace-v2' if retained else 'native-javac-source-trace-v1'
  if len(events)<2 or any(not isinstance(event,dict) or event.get('format')!=event_format or event.get('attempt')!=attempt or type(event.get('invocation')) is not int or event['invocation']!=invocation or event.get('consumptionCertified') is not False for event in events):raise FrameAdmissionError('native parser task/attempt identity mismatch')
  terminal=_runtime_event(events[0],toolchain) if runtime else 'COMPILATION_END'
  if events[0].get('phase')!='TASK_INITIALIZED' or events[-1].get('phase')!=terminal or type(events[-1].get('parsedUnits')) is not int or events[-1]['parsedUnits']!=len(events)-2:raise FrameAdmissionError('native parser task/process incomplete')
  units=[]
  for index,event in enumerate(events[1:-1],1):
   if event.get('phase')!='PARSED_UNIT' or type(event.get('index')) is not int or event['index']!=index or not _sha(event.get('reportedSourceCharSha256')):raise FrameAdmissionError('native parsed-unit census incomplete')
   uri=event.get('uri')
   if not isinstance(uri,str):raise FrameAdmissionError('native source URI unavailable')
   parsed=urlsplit(uri);path=unquote(parsed.path,errors='strict')
   if parsed.scheme!='file' or parsed.netloc or parsed.query or parsed.fragment or not _path(path):raise FrameAdmissionError('native source URI profile unsupported')
   declarations=event.get('declarations')
   if not isinstance(declarations,list):raise FrameAdmissionError('native declarations unavailable')
   for declaration in declarations:
    if not isinstance(declaration,dict) or not isinstance(declaration.get('name'),str) or any(type(declaration.get(key)) is not int for key in ('start','end')) or declaration['start']<0 or declaration['end']<=declaration['start']:raise FrameAdmissionError('native declaration position unsupported')
   units.append(dict(path=path,origin='EXPLICIT_OPERAND' if path in config['getSourceFiles'] else 'ADDITIONAL_PARSED_UNIT',reportedSourceCharSha256=event['reportedSourceCharSha256'],declarations=declarations))
  if not set(config['getSourceFiles']).issubset({unit['path'] for unit in units}):raise FrameAdmissionError('explicit source missing from native parsed population')
  item=dict(frame=row['frame'],invocation=invocation,state='NATIVE_PARSED_POPULATION',units=units)
  if runtime:item.update(compilerToolchain=toolchain,runtimeObserved=True,completionStrategy=events[0]['completionStrategy'],effectiveOptions=events[0]['effectiveOptions'])
  if semantic:
   from semantic_receipt import validate_semantic_transport
   semantic_identity=monitor['semanticArtifact']
   if not isinstance(semantic_identity,dict) or set(semantic_identity)!={'sha256','bytes'} or not _sha(semantic_identity.get('sha256')) or type(semantic_identity.get('bytes')) is not int or semantic_identity['bytes']<=0:raise FrameAdmissionError('invalid semantic artifact identity')
   item['semanticTransport']=validate_semantic_transport(load(semantic_name),semantic_identity,units,attempt,invocation,toolchain);files.append(semantic_name)
  if retained:item['retainedInputs']=retain(retained_name,monitor['retainedInputs'],events[1:-1],config);files.append(retained_name)
  population.append(item)
 result.update(kind='VALIDATED_NATIVE_JAVAC_PARSED_POPULATION',parsedInvocations=population,parserArtifacts=files,sourcePopulationScope='Native PARSE units in the qualified monitored task; snapshots are not consumed buffers.',canonicalAdmissionCertified=False)
 return result


def main():
 import argparse,json
 from pathlib import Path
 parser=argparse.ArgumentParser(description=__doc__)
 for name in ('metadata','compiler','session','parsed-root'):parser.add_argument('--'+name,type=Path,required=True)
 for name in ('attempt','logical-parsed-root','plugin-sha256'):parser.add_argument('--'+name,required=True)
 parser.add_argument('--build-exit',type=int,required=True);args=parser.parse_args()
 try:
  if args.parsed_root.is_symlink() or not args.parsed_root.is_dir():raise FrameAdmissionError('regular parser artifact directory required')
  seals=read_frames(args.session)
  if len(seals)!=1:raise FrameAdmissionError('exactly one native session seal required')
  from retained_inputs import validate_retained_inputs
  result=validate_parser_attempt(read_frames(args.metadata,with_identity=True),read_frames(args.compiler,with_identity=True),seals[0],args.attempt,args.build_exit,args.logical_parsed_root,args.plugin_sha256,lambda name:read_frames(args.parsed_root/name,with_identity=True),lambda name,descriptor,units,config:validate_retained_inputs(args.parsed_root/name,descriptor,units,config))
  if sorted(p.name for p in args.parsed_root.iterdir())!=sorted(result['parserArtifacts']):raise FrameAdmissionError('unbound or missing native parser artifacts')
 except (FrameAdmissionError,OSError,UnicodeError,ValueError,LookupError) as problem:parser.exit(70,str(problem)+'\n')
 print(json.dumps(result));return 0


if __name__=='__main__':raise SystemExit(main())
