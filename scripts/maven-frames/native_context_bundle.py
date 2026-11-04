"""Construct an rc52 Java context bundle from a recorded native Maven execution.

This checks recorded provenance and retained snapshots, not adversarial attestation
or actual consumed buffers. No build is run and no formal result is manufactured.
"""
from pathlib import Path,PurePosixPath
import codecs,hashlib,json,os,re,shutil
from compiler_receipt import _path,_sha
from frame_receipt import FrameAdmissionError,read_frames
from parser_receipt import validate_parser_attempt
from retained_inputs import validate_retained_inputs,_relative


def digest(path):
 h=hashlib.sha256();size=0
 with path.open('rb') as f:
  while chunk:=f.read(8192):
   size+=len(chunk)
   if size>64*1024*1024:raise FrameAdmissionError('source/plugin byte policy exceeded')
   h.update(chunk)
 return h.hexdigest()


def regular_root(path):
 path=Path(path)
 if not path.is_absolute() or path!=Path(os.path.normpath(path)) or any(p.is_symlink() for p in [path,*path.parents]) or not path.is_dir():raise FrameAdmissionError('absolute regular input directory required')
 return path


def source_inventory(root):
 """Full Java inventory, including files no native invocation parsed."""
 root=regular_root(root);result={};nodes=0;total=0
 for folder,dirs,files in os.walk(root,followlinks=False):
  for name in dirs+files:
   p=Path(folder)/name;nodes+=1
   if nodes>65536 or p.is_symlink():raise FrameAdmissionError('source tree symlink/node policy exceeded')
  for name in files:
   if not name.endswith('.java'):continue
   p=Path(folder)/name
   if not p.is_file():raise FrameAdmissionError('nonregular Java source')
   total+=p.stat().st_size
   if total>512*1024*1024:raise FrameAdmissionError('source inventory byte policy exceeded')
   result[p.relative_to(root).as_posix()]=digest(p)
 return result


def execution_binding(envelope,source,output,collector,plugin,helper_bytes=None):
 collector_bytes,plugin_bytes=helper_bytes or (collector,plugin)
 if not isinstance(envelope,dict) or envelope.get('format')!='native-java-execution-envelope-v1':raise FrameAdmissionError('native execution envelope required')
 if not isinstance(envelope.get('attempt'),str) or not re.fullmatch('[0-9a-f]{32}',envelope['attempt']):raise FrameAdmissionError('native execution attempt unavailable')
 image=envelope.get('image');stage=envelope.get('stage')
 if not isinstance(image,str) or not re.fullmatch('sha256:[0-9a-f]{64}',image) or not isinstance(stage,dict) or type(stage.get('exit')) is not int or stage['exit']!=0 or stage.get('timedOut',False) is not False:raise FrameAdmissionError('successful immutable-image execution required')
 argv=stage.get('command')
 if not isinstance(argv,list) or any(not isinstance(a,str) or any(c in a for c in '\0\n\r') for a in argv) or argv[:2]!=['docker','run'] or '--entrypoint' in argv or argv.count('--name')!=1:raise FrameAdmissionError('unsupported native execution command')
 index=argv.index('--name')
 if len(argv)<=index+3 or argv[index+2]!=image or argv[index+3]!='mvn':raise FrameAdmissionError('image/Maven command binding mismatch')
 # Only the bounded native execution shape is reconstructible here. In
 # particular, mounts/environment overrides must not replace the image runtime.
 allowed_flags={'--rm','--read-only','--network=none','--cap-drop=ALL','--security-opt=no-new-privileges'}
 options={'--mount','--tmpfs','--memory','--pids-limit','-w','-e'};i=2
 while i<index:
  option=argv[i]
  if option in allowed_flags:i+=1;continue
  if option not in options or i+1>=index:raise FrameAdmissionError('native Docker option profile unqualified')
  value=argv[i+1]
  if option=='-e':
   key,sep,data=value.partition('=')
   if not sep or key not in ('HOME','MAVEN_CONFIG','MAVEN_OPTS'):raise FrameAdmissionError('native runtime environment override unqualified')
   if key=='MAVEN_OPTS' and any(not re.fullmatch(r'(?:-Xm[xs][0-9]+[kKmMgG]|-D(?:java.io.tmpdir|jansi.tmpdir)=[/A-Za-z0-9_.-]+|-Djansi.force=(?:true|false))',token) for token in data.split()):raise FrameAdmissionError('native JVM instrumentation/options unqualified')
  if option=='--tmpfs' and not value.startswith('/tmp:'):raise FrameAdmissionError('native runtime tmpfs override unqualified')
  i+=2
 mounts={}
 for i,arg in enumerate(argv[:index]):
  if arg!='--mount':continue
  if i+1>=index:raise FrameAdmissionError('incomplete mount binding')
  parts=argv[i+1].split(',');values={}
  for part in parts:
   key,sep,value=part.partition('=')
   if key in values:raise FrameAdmissionError('duplicate mount option')
   values[key]=value if sep else True
  if set(values)-{'type','src','dst','readonly'} or values.get('type')!='bind' or not _path(values.get('src')) or not _path(values.get('dst')) or values['dst'] in mounts:raise FrameAdmissionError('unsupported/duplicate native bind mount')
  if not (values['dst'].startswith('/workspace/') or values['dst']=='/output'):raise FrameAdmissionError('native runtime bind override unqualified')
  mounts[values['dst']]=values
 def mounted(host):
  found=[dst for dst,m in mounts.items() if m['src']==str(host)]
  if len(found)!=1:raise FrameAdmissionError('native input mount unavailable or ambiguous')
  return found[0]
 native_source=mounted(source);native_output=mounted(output);native_collector=mounted(collector);native_plugin=mounted(plugin)
 for dst in mounts:
  if any(dst!=root and PurePosixPath(dst).is_relative_to(root) for root in (native_source,native_output)):raise FrameAdmissionError('nested native source/output mount unqualified')
 properties={}
 for token in argv[index+4:]:
  if token.startswith('-T') or token.startswith('--threads'):raise FrameAdmissionError('parallel native execution needs a deterministic occurrence contract')
  if token.startswith('-D'):
   key,sep,value=token[2:].partition('=')
   if not sep or key in properties:raise FrameAdmissionError('unsupported/duplicate Maven property')
   properties[key]=value
 expected={'conformance.compiler.attempt':envelope['attempt'],'conformance.compiler.sourcePlugin':native_plugin,'conformance.compiler.sourcePluginSha256':digest(plugin_bytes)}
 if any(properties.get(k)!=value for k,value in expected.items()) or properties.get('conformance.compiler.retainInputs')!='true':raise FrameAdmissionError('native capture arguments do not bind the retained profile')
 if native_collector not in properties.get('maven.ext.class.path','').split(':'):raise FrameAdmissionError('native collector extension not loaded')
 for file,key in [(collector_bytes,'collectorJarSha256'),(plugin_bytes,'sourcePluginJarSha256')]:
  if not _sha(envelope.get(key)) or digest(file)!=envelope[key]:raise FrameAdmissionError('native helper byte identity mismatch')
 paths={}
 for role,key in [('metadata','conformance.frames.receipt'),('compiler','conformance.compiler.receipt'),('session','conformance.compiler.session'),('parsed','conformance.compiler.parsedRoot')]:
  value=properties.get(key)
  if not _path(value):raise FrameAdmissionError('native capture output path unavailable')
  try:relative=PurePosixPath(value).relative_to(native_output)
  except ValueError:raise FrameAdmissionError('capture output outside declared mount')
  _relative(relative.as_posix());paths[role]=output/relative.as_posix()
  if any(p.is_symlink() for p in [paths[role],*paths[role].parents]):raise FrameAdmissionError('symlink capture output')
 paths.update(logicalParsedRoot=properties['conformance.compiler.parsedRoot'],nativeSource=native_source,image=image)
 return paths


def profile_rows(config,fields):
 if config.get('isFork') is not False or config.get('getExecutable') is not None or config.get('getModulepathEntries')!=[] or config.get('getProc')!='none' or fields.get('enablePreview') is not False:raise FrameAdmissionError('compiler fork/module/processor/preview profile unqualified')
 if not isinstance(config.get('getSourceEncoding'),str) or codecs.lookup(config['getSourceEncoding']).name!='utf-8':raise FrameAdmissionError('non-UTF8 observation profile unqualified')
 release=config.get('getReleaseVersion')
 if not isinstance(release,str) or not re.fullmatch('[0-9]+',release) or not 7<=int(release)<=17:raise FrameAdmissionError('native JDK17 release profile unavailable')
 args=config.get('orderedCustomArguments')
 if not isinstance(args,list):raise FrameAdmissionError('ordered native arguments unavailable')
 tokens=[]
 for pair in args:
  if not isinstance(pair,dict) or set(pair)!={'key','value'} or pair['value'] not in (None,'') or not isinstance(pair['key'],str) or not re.fullmatch(r'-(?:parameters|g(?::(?:none|lines|vars|source)(?:,(?:lines|vars|source))*)?|nowarn|deprecation|Werror|Xlint(?::[-A-Za-z,]+)?)',pair['key']):raise FrameAdmissionError('native compiler option requires a qualified reconstruction rule')
  tokens.append(pair['key'])
 return release,tokens


def context_id(module,goal,execution_id,occurrence):
 identity=json.dumps([module,goal,execution_id,occurrence],ensure_ascii=False,separators=(',',':'))
 return 'native|'+hashlib.sha256(identity.encode('utf-8')).hexdigest()


def plan_contexts(envelope,bound,inventory,metadata,compiler,validated,logical_bundle):
 calls={row['invocation']:row for row in compiler[0] if row['phase']=='RETURN'};frames={row['frame']:row for row in metadata[0] if row['phase']=='SUCCESS'};rows=[];worlds=[];snapshot_sources={}
 occurrences={}
 for population in validated['parsedInvocations']:
  if population['state']!='NATIVE_PARSED_POPULATION' or 'retainedInputs' not in population:raise FrameAdmissionError('unobserved/unretained invocation cannot become a source context')
  call=calls[population['invocation']];config=call['configuration'];frame=frames[population['frame']];release,tokens=profile_rows(config,frame['fields'])
  try:module=PurePosixPath(frames[population['frame']]['module']).relative_to(bound['nativeSource']).as_posix()
  except ValueError:raise FrameAdmissionError('native module outside source mount')
  _relative(module,dot=True)
  if any(c in module for c in '\t\r\n'):raise FrameAdmissionError('native module contains manifest separators')
  key=(module,frame['goal'],frame['executionId']);occurrences[key]=occurrences.get(key,0)+1
  cid=context_id(*key,occurrences[key])
  members=[]
  for entry in population['retainedInputs']['sources']:
   from urllib.parse import urlsplit,unquote
   uri=urlsplit(entry['uri'])
   try:relative=PurePosixPath(unquote(uri.path,errors='strict')).relative_to(bound['nativeSource']).as_posix()
   except ValueError:raise FrameAdmissionError('parsed source outside source mount requires a multi-root contract')
   _relative(relative)
   if inventory.get(relative)!=entry['snapshot']['sha256']:raise FrameAdmissionError('native parsed source differs from full source inventory')
   snapshot=bound['parsed']/('invocation-'+str(population['invocation'])+'.inputs')/entry['snapshot']['path']
   if relative in snapshot_sources and digest(snapshot_sources[relative])!=digest(snapshot):raise FrameAdmissionError('contextual source versions require separate source-world support')
   snapshot_sources[relative]=snapshot;members.append(relative)
  if len(members)!=len(set(members)):raise FrameAdmissionError('repeated parsed source needs an occurrence contract')
  # Preserve native search roots separately from observed membership. A PARSE
  # event cannot invent an additional configured root or generation provenance.
  roots=[]
  for native_root in config['getSourceLocations']:
   try:relative_root=PurePosixPath(native_root).relative_to(bound['nativeSource']).as_posix()
   except ValueError:raise FrameAdmissionError('native source root requires a multi-root contract')
   _relative(relative_root,dot=True);roots.append(relative_root)
  if not roots or any(not any(r=='.' or n==r or n.startswith(r+'/') for r in roots) for n in members):raise FrameAdmissionError('native PARSE population outside configured source roots is not representable')
  rows.append(['context',cid,module,'','',release,'false',''])
  rows.extend([['source',cid,r] for r in roots])
  rows.append(['source-population',cid,'exact']);rows.extend([['source-member',cid,n] for n in sorted(members)])
  native_output=config.get('getOutputLocation')
  if native_output is not None:
   if not _path(native_output):raise FrameAdmissionError('native compiler output coordinate unavailable')
   rows.append(['output',cid,native_output])
  for role,kind in [('classpath','classpath'),('processorPath','processor-path')]:
   entries=population['retainedInputs']['resolution'][role]
   if entries is None:continue
   originals=[e['original'] for e in entries]
   if len(originals)!=len(set(originals)):raise FrameAdmissionError('duplicate resolution entries need explicit consumer equivalence qualification')
   for entry in entries:rows.append([kind,cid,logical_bundle+'/inputs/invocation-'+str(population['invocation'])+'.inputs/'+entry['snapshot']])
  rows.extend([['compiler-arg',cid,t] for t in tokens]);worlds.append(dict(context=cid,frame=population['frame'],invocation=population['invocation'],members=sorted(members),nativeConfiguration=config))
 if not worlds:raise FrameAdmissionError('no observed native compiler worlds; source-only fallback forbidden')
 manifest=''.join('\t'.join(row)+'\n' for row in rows)
 return manifest,worlds,snapshot_sources


def build_bundle(envelope,source,output,collector,plugin,destination,logical_bundle):
 source=regular_root(source);output=regular_root(output);destination=Path(destination)
 for helper in (collector,plugin):
  if not helper.is_absolute() or any(p.is_symlink() for p in [helper,*helper.parents]) or not helper.is_file():raise FrameAdmissionError('regular absolute helper JAR required')
 if not _path(logical_bundle) or PurePosixPath(logical_bundle).as_posix()!=logical_bundle:raise FrameAdmissionError('canonical absolute bundle mount required')
 if destination.exists() or destination.is_symlink() or not destination.is_absolute() or destination!=Path(os.path.normpath(destination)) or any(p.is_symlink() for p in destination.parents):raise FrameAdmissionError('fresh canonical bundle destination required')
 for input_root in (source,output):
  if destination.is_relative_to(input_root) or input_root.is_relative_to(destination):raise FrameAdmissionError('bundle overlaps native inputs')
 bound=execution_binding(envelope,source,output,collector,plugin)
 native_source=PurePosixPath(bound['nativeSource']);logical_destination=PurePosixPath(logical_bundle)
 if logical_destination.is_relative_to(native_source) or native_source.is_relative_to(logical_destination):raise FrameAdmissionError('logical source and retained-input mounts overlap')
 inventory=envelope.get('sourceInventory')
 if not isinstance(inventory,dict) or any(not isinstance(n,str) or not n.endswith('.java') or not _sha(h) for n,h in inventory.items()):raise FrameAdmissionError('full native Java inventory required')
 for name in inventory:_relative(name)
 if source_inventory(source)!=inventory:raise FrameAdmissionError('native source inventory/bytes changed')
 metadata=read_frames(bound['metadata'],with_identity=True);compiler=read_frames(bound['compiler'],with_identity=True);seals=read_frames(bound['session'])
 if len(seals)!=1:raise FrameAdmissionError('one native seal required')
 validated=validate_parser_attempt(metadata,compiler,seals[0],envelope['attempt'],0,bound['logicalParsedRoot'],envelope['sourcePluginJarSha256'],lambda n:read_frames(bound['parsed']/n,with_identity=True),lambda n,d,u,c:validate_retained_inputs(bound['parsed']/n,d,u,c))
 if sorted(p.name for p in bound['parsed'].iterdir())!=sorted(validated['parserArtifacts']):raise FrameAdmissionError('unbound parser artifact')
 manifest,worlds,snapshot_sources=plan_contexts(envelope,bound,inventory,metadata,compiler,validated,logical_bundle)
 destination.mkdir()
 try:
  (destination/'source').mkdir()
  for name,h in sorted(inventory.items()):
   original=snapshot_sources.get(name,source/name);target=destination/'source'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(original,target)
   if digest(target)!=h:raise FrameAdmissionError('source changed while constructing bundle')
  shutil.copytree(bound['parsed'],destination/'inputs',copy_function=shutil.copy2)
  shutil.copy2(collector,destination/'compiler-frames.jar');shutil.copy2(plugin,destination/'source-trace.jar')
  if digest(destination/'compiler-frames.jar')!=envelope['collectorJarSha256'] or digest(destination/'source-trace.jar')!=envelope['sourcePluginJarSha256']:raise FrameAdmissionError('helper changed while constructing bundle')
  capture=destination/'capture';capture.mkdir()
  for key in ('metadata','compiler','session'):shutil.copy2(bound[key],capture/(key+'.jsonl'))
  (destination/'contexts.tsv').write_text(manifest,encoding='utf-8')
  (destination/'execution.jsonl').write_text(json.dumps(envelope,ensure_ascii=False)+'\n',encoding='utf-8')
  if source_inventory(source)!=inventory:raise FrameAdmissionError('native sources changed during bundle construction')
  # Validate copied artifacts, not merely the originals from which they came.
  copied=validate_parser_attempt(read_frames(capture/'metadata.jsonl',with_identity=True),read_frames(capture/'compiler.jsonl',with_identity=True),read_frames(capture/'session.jsonl')[0],envelope['attempt'],0,bound['logicalParsedRoot'],digest(destination/'source-trace.jar'),lambda n:read_frames(destination/'inputs'/n,with_identity=True),lambda n,d,u,c:validate_retained_inputs(destination/'inputs'/n,d,u,c))
  if copied!=validated or source_inventory(destination/'source')!=inventory:raise FrameAdmissionError('bundle differs from validated native inputs')
  result=dict(format='native-java-context-bundle-v1',recordedMounts=dict(source=str(source),output=str(output),collector=str(collector),plugin=str(plugin)),image=bound['image'],logicalSourceRoot=bound['nativeSource'],logicalBundleRoot=logical_bundle,attempt=envelope['attempt'],sourceInventory=inventory,worlds=worlds,unobservedFrames=[f for f in validated['frames'] if f['invocationCount']==0],executionSha256=digest(destination/'execution.jsonl'),manifestSha256=digest(destination/'contexts.tsv'),collectorJarSha256=digest(destination/'compiler-frames.jar'),sourcePluginJarSha256=digest(destination/'source-trace.jar'),consumptionCertified=False,canonicalAdmissionCertified=False,scope='Observed PARSE membership and before-call resolution; unparsed inventory retained, no generated provenance inferred; same recorded image required.')
  encoded=(json.dumps(result,indent=2)+'\n').encode('utf-8')
  if len(encoded)>64*1024*1024:raise FrameAdmissionError('bundle binding exceeds64MiB policy')
  (destination/'binding.json').write_bytes(encoded);return result
 except BaseException:
  # Retain the failed directory for investigation; no binding.json is emitted.
  raise


def verify_bundle(root,expected_binding_sha):
 """Revalidate an independently moved bundle without its original host paths."""
 root=regular_root(root)
 if {p.name for p in root.iterdir()}!={'source','inputs','capture','compiler-frames.jar','source-trace.jar','contexts.tsv','execution.jsonl','binding.json'} or any(p.is_symlink() for p in root.iterdir()):raise FrameAdmissionError('unbound/missing bundle artifact')
 if any(not (root/n).is_file() for n in ('compiler-frames.jar','source-trace.jar','contexts.tsv','execution.jsonl','binding.json')):raise FrameAdmissionError('regular bundle files required')
 if not _sha(expected_binding_sha) or digest(root/'binding.json')!=expected_binding_sha:raise FrameAdmissionError('external bundle binding identity mismatch')
 def unique(pairs):
  result={}
  for k,v in pairs:
   if k in result:raise FrameAdmissionError('duplicate binding field')
   result[k]=v
  return result
 if (root/'binding.json').stat().st_size>64*1024*1024:raise FrameAdmissionError('bundle binding byte policy exceeded')
 binding=json.loads((root/'binding.json').read_text(encoding='utf-8'),object_pairs_hook=unique)
 if not isinstance(binding,dict) or binding.get('format')!='native-java-context-bundle-v1' or binding.get('canonicalAdmissionCertified') is not False or binding.get('consumptionCertified') is not False:raise FrameAdmissionError('unsupported bundle binding/certification')
 for name,key in [('contexts.tsv','manifestSha256'),('execution.jsonl','executionSha256'),('compiler-frames.jar','collectorJarSha256'),('source-trace.jar','sourcePluginJarSha256')]:
  if not _sha(binding.get(key)) or digest(root/name)!=binding[key]:raise FrameAdmissionError('bound bundle file changed: '+name)
 envelopes=read_frames(root/'execution.jsonl')
 if len(envelopes)!=1:raise FrameAdmissionError('one bound execution required')
 envelope=envelopes[0];mounts=binding.get('recordedMounts')
 if not isinstance(mounts,dict) or set(mounts)!={'source','output','collector','plugin'} or any(not _path(p) for p in mounts.values()):raise FrameAdmissionError('recorded mount coordinates unavailable')
 # These old host coordinates are compared to the recorded argv only. Never
 # dereference them: all artifact reads below are inside this restored bundle.
 bound=execution_binding(envelope,*(Path(mounts[k]) for k in ('source','output','collector','plugin')),helper_bytes=(root/'compiler-frames.jar',root/'source-trace.jar'))
 if any(binding.get(k)!=value for k,value in [('image',bound['image']),('logicalSourceRoot',bound['nativeSource']),('attempt',envelope['attempt']),('sourceInventory',envelope.get('sourceInventory'))]):raise FrameAdmissionError('bundle execution binding mismatch')
 logical=binding.get('logicalBundleRoot')
 if not _path(logical) or PurePosixPath(logical).as_posix()!=logical or PurePosixPath(logical).is_relative_to(bound['nativeSource']) or PurePosixPath(bound['nativeSource']).is_relative_to(logical):raise FrameAdmissionError('invalid restored bundle mount')
 capture=regular_root(root/'capture');parsed=regular_root(root/'inputs')
 if {p.name for p in capture.iterdir()}!={'metadata.jsonl','compiler.jsonl','session.jsonl'}:raise FrameAdmissionError('unbound capture artifact')
 metadata=read_frames(capture/'metadata.jsonl',with_identity=True);compiler=read_frames(capture/'compiler.jsonl',with_identity=True);seals=read_frames(capture/'session.jsonl')
 if len(seals)!=1:raise FrameAdmissionError('one restored native session seal required')
 bound['parsed']=parsed
 validated=validate_parser_attempt(metadata,compiler,seals[0],envelope['attempt'],0,bound['logicalParsedRoot'],binding['sourcePluginJarSha256'],lambda n:read_frames(parsed/n,with_identity=True),lambda n,d,u,c:validate_retained_inputs(parsed/n,d,u,c))
 if sorted(p.name for p in parsed.iterdir())!=sorted(validated['parserArtifacts']):raise FrameAdmissionError('unbound restored parser artifact')
 inventory=source_inventory(root/'source')
 if inventory!=envelope.get('sourceInventory'):raise FrameAdmissionError('restored source inventory changed')
 manifest,worlds,_=plan_contexts(envelope,bound,inventory,metadata,compiler,validated,logical)
 if (root/'contexts.tsv').read_text(encoding='utf-8')!=manifest or binding.get('worlds')!=worlds or binding.get('unobservedFrames')!=[f for f in validated['frames'] if f['invocationCount']==0]:raise FrameAdmissionError('manifest/worlds differ from native reconstruction')
 return binding


def main():
 import argparse
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--verify',type=Path)
 parser.add_argument('--expected-binding-sha256')
 for name in ('execution','source-root','output-root','collector','source-plugin','destination'):parser.add_argument('--'+name,type=Path)
 parser.add_argument('--logical-bundle-root');args=parser.parse_args()
 try:
  if args.verify:
   if any(getattr(args,n) is not None for n in ('execution','source_root','output_root','collector','source_plugin','destination','logical_bundle_root')):raise FrameAdmissionError('verify cannot override recorded construction arguments')
   result=verify_bundle(args.verify,args.expected_binding_sha256)
  else:
   if args.expected_binding_sha256 is not None or any(getattr(args,n) is None for n in ('execution','source_root','output_root','collector','source_plugin','destination','logical_bundle_root')):raise FrameAdmissionError('all construction arguments are required; external binding hash is for verify only')
   rows=read_frames(args.execution)
   if len(rows)!=1:raise FrameAdmissionError('one native execution envelope required')
   result=build_bundle(rows[0],args.source_root,args.output_root,args.collector,args.source_plugin,args.destination,args.logical_bundle_root)
 except (FrameAdmissionError,OSError,UnicodeError,ValueError,LookupError) as error:parser.exit(70,str(error)+'\n')
 print(json.dumps(dict(binding=result,bindingFileSha256=digest((args.verify or args.destination)/'binding.json'))));return 0


if __name__=='__main__':raise SystemExit(main())
