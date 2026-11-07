"""Self-contained recorded author-task source inputs; no build or formal verdict.

Uses the existing native guards. Snapshots never certify full consumption or
unique binary origins. POSIX retained metadata must survive bundle transfer.
"""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,os,shutil,sys
from urllib.parse import urlsplit,unquote
sys.path.insert(0,str(Path(__file__).resolve().parent))
from native_context_bundle import regular_root,source_inventory,digest,context_id
from frame_receipt import FrameAdmissionError,read_frames
from compiler_receipt import _path,_sha
from parser_receipt import validate_parser_attempt
from retained_inputs import validate_retained_inputs,_relative
from javac_response import bind_fork_responses

FORMAT='native-java-source-bundle-v1'


def document(path):
 if path.is_symlink() or not path.is_file() or path.stat().st_size>8*1024*1024:raise FrameAdmissionError('bounded regular bundle document required')
 def unique(pairs):
  result={}
  for key,value in pairs:
   if key in result:raise FrameAdmissionError('duplicate bundle JSON field')
   result[key]=value
  return result
 return json.loads(path.read_text(encoding='utf-8-sig'),object_pairs_hook=unique)


def file_inventory(root):
 result={};nodes=0;total=0
 for folder,dirs,files in os.walk(root,followlinks=False):
  for name in dirs+files:
   path=Path(folder)/name;nodes+=1
   if nodes>65536 or path.is_symlink():raise FrameAdmissionError('bundle symlink/node policy exceeded')
  for name in files:
   path=Path(folder)/name;relative=path.relative_to(root).as_posix()
   if relative=='bundle.json':continue
   if not path.is_file():raise FrameAdmissionError('nonregular bundle artifact')
   size=path.stat().st_size;total+=size
   if size>64*1024*1024 or total>1024*1024*1024:raise FrameAdmissionError('bundle byte policy exceeded')
   result[relative]=dict(sha256=digest(path),bytes=size)
 return result


def verify(root):
 root=regular_root(root);manifest=document(root/'bundle.json')
 expected={'format','attempt','buildExit','sourceRoot','pluginSha256','collectorSha256','inventory','files'}
 if not isinstance(manifest,dict) or set(manifest)!=expected or manifest.get('format')!=FORMAT or type(manifest.get('buildExit')) is not int or manifest['buildExit']!=0 or not _path(manifest.get('sourceRoot')):raise FrameAdmissionError('successful recorded native source bundle required')
 files=manifest['files'];inventory=manifest['inventory']
 if not isinstance(files,dict) or not isinstance(inventory,dict) or not inventory:raise FrameAdmissionError('full source/artifact inventory required')
 for name in files:_relative(name)
 for name,value in inventory.items():
  _relative(name)
  if not name.endswith('.java') or not _sha(value):raise FrameAdmissionError('invalid native Java source inventory')
 if file_inventory(root)!=files or source_inventory(root/'source')!=inventory:raise FrameAdmissionError('native bundle bytes/population changed')
 if digest(root/'source-plugin.jar')!=manifest['pluginSha256'] or digest(root/'collector.jar')!=manifest['collectorSha256']:raise FrameAdmissionError('native helper byte identity differs')
 execution=document(root/'execution.json')
 if execution.get('exitCode')!=0 or type(execution.get('exitCode')) is not int:raise FrameAdmissionError('successful owned build exit required')
 metadata=read_frames(root/'capture/metadata.jsonl',with_identity=True);compiler=read_frames(root/'capture/compiler.jsonl',with_identity=True);seal=read_frames(root/'capture/session.jsonl')
 if len(seal)!=1:raise FrameAdmissionError('one sealed compiler session required')
 calls=[row for row in compiler[0] if row['phase']=='RETURN']
 logical_roots={str(PurePosixPath(row.get('sourceMonitor',{}).get('output','')).parent) for row in calls}
 if len(logical_roots)!=1:raise FrameAdmissionError('one bound parser artifact root required')
 logical=next(iter(logical_roots));parsed=root/'parsed'
 validated=validate_parser_attempt(metadata,compiler,seal[0],manifest['attempt'],0,logical,manifest['pluginSha256'],
  lambda name:read_frames(parsed/name,with_identity=True),lambda name,desc,units,config:validate_retained_inputs(parsed/name,desc,units,config))
 if sorted(path.name for path in parsed.iterdir())!=sorted(validated['parserArtifacts']):raise FrameAdmissionError('unbound native parser artifacts')
 responses=bind_fork_responses(sorted((root/'responses').iterdir()),compiler[0],validated)
 response_by_call={row['invocation']:row for row in responses};frame_by_id={row['frame']:row for row in metadata[0] if row['phase']=='SUCCESS'}
 counts={};worlds=[];owned=set();native_root=PurePosixPath(manifest['sourceRoot'])
 for population in validated['parsedInvocations']:
  if population['state']!='NATIVE_PARSED_POPULATION' or 'semanticTransport' not in population:raise FrameAdmissionError('actual-task semantic source capture required in every world')
  invocation=population['invocation'];call=next(row for row in calls if row['invocation']==invocation);frame=frame_by_id[population['frame']]
  try:module=PurePosixPath(frame['module']).relative_to(native_root).as_posix()
  except ValueError:raise FrameAdmissionError('native module outside declared source root')
  _relative(module,dot=True);key=(module,frame['goal'],frame['executionId']);counts[key]=counts.get(key,0)+1;cid=context_id(*key,counts[key])
  units=[]
  for unit in population['units']:
   try:relative=PurePosixPath(unit['path']).relative_to(native_root).as_posix()
   except ValueError:raise FrameAdmissionError('additional native source roots need a qualified multi-root contract')
   _relative(relative)
   snapshots=[entry for entry in population['retainedInputs']['sources'] if unquote(urlsplit(entry['uri']).path,errors='strict')==unit['path']]
   if len(snapshots)!=1 or inventory.get(relative)!=snapshots[0]['snapshot']['sha256']:raise FrameAdmissionError('native source snapshot differs from full source inventory')
   owned.add(relative);units.append(dict(path=relative,uri=snapshots[0]['uri'],sha256=inventory[relative],origin=unit['origin'],declarations=unit['declarations']))
  roots=[]
  for path in call['configuration']['getSourceLocations']:
   try:relative=PurePosixPath(path).relative_to(native_root).as_posix()
   except ValueError:raise FrameAdmissionError('native source root outside declared project')
   _relative(relative,dot=True);roots.append(relative)
  if not roots:raise FrameAdmissionError('native configured source roots required')
  worlds.append(dict(contextId=cid,module=module,goal=frame['goal'],executionId=frame['executionId'],occurrence=counts[key],
   invocation=invocation,feature=population['compilerToolchain']['feature'],toolchain=population['compilerToolchain'],
   facts='parsed/invocation-'+str(invocation)+'.semantic.jsonl',factsSha256=population['semanticTransport']['artifact']['sha256'],
   sourceRoots=roots,sources=units,invocationReceipt=response_by_call[invocation],retainedInputs=population['retainedInputs'],
   compilerClassSha256=call['compilerClassSha256'],compilerConfiguration=call['configuration']))
 if not worlds:raise FrameAdmissionError('no actual author compilation worlds')
 return dict(format=FORMAT,bundleSha256=digest(root/'bundle.json'),attempt=manifest['attempt'],sourceRoot=manifest['sourceRoot'],
  inventory=inventory,unownedSources=sorted(set(inventory)-owned),files=files,worlds=worlds,
  scope='Actual selected author task, source declarations only; snapshots do not certify full consumption or unique binary origins')


def create(source,capture,execution,collector,plugin,response_root,destination):
 source=regular_root(source);capture=regular_root(capture);response_root=regular_root(response_root);destination=Path(destination)
 if destination.exists() or not destination.is_absolute() or any(path.is_symlink() for path in destination.parents):raise FrameAdmissionError('fresh absolute native bundle destination required')
 for path in (source,capture,response_root):
  if destination.is_relative_to(path) or path.is_relative_to(destination):raise FrameAdmissionError('native bundle overlaps its inputs')
 inventory=source_inventory(source);parent=document(execution)
 if type(parent.get('exitCode')) is not int or parent['exitCode']!=0:raise FrameAdmissionError('owned successful build exit required')
 compiler=read_frames(capture/'compiler.jsonl');starts=[row for row in compiler if row['phase']=='START']
 if not starts:raise FrameAdmissionError('native compiler starts missing')
 destination.mkdir();(destination/'source').mkdir();(destination/'capture').mkdir();(destination/'responses').mkdir()
 for name,value in inventory.items():
  target=destination/'source'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source/name,target)
  if digest(target)!=value:raise FrameAdmissionError('source changed during bundle copy')
 for before,after in [('frames.jsonl','metadata.jsonl'),('compiler.jsonl','compiler.jsonl'),('session.jsonl','session.jsonl')]:shutil.copy2(capture/before,destination/'capture'/after)
 shutil.copytree(capture/'parsed',destination/'parsed',copy_function=shutil.copy2)
 for index,path in enumerate(sorted(response_root.glob('org.codehaus.plexus.compiler.javac.JavacCompiler*arguments')),1):shutil.copy2(path,destination/'responses'/('response-'+str(index)+'.arguments'))
 shutil.copy2(execution,destination/'execution.json');shutil.copy2(collector,destination/'collector.jar');shutil.copy2(plugin,destination/'source-plugin.jar')
 manifest=dict(format=FORMAT,attempt=starts[0]['attempt'],buildExit=parent['exitCode'],sourceRoot=str(source),pluginSha256=digest(plugin),collectorSha256=digest(collector),inventory=inventory,files=file_inventory(destination))
 (destination/'bundle.json').write_text(json.dumps(manifest,indent=2)+'\n')
 result=verify(destination)
 if source_inventory(source)!=inventory:raise FrameAdmissionError('source changed during native bundle construction')
 return result


def main():
 p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
 check=sub.add_parser('verify');check.add_argument('--bundle',type=Path,required=True)
 build=sub.add_parser('create')
 for name in ('source','capture','execution','collector','plugin','response-root','destination'):build.add_argument('--'+name,type=Path,required=True)
 args=p.parse_args()
 try:result=verify(args.bundle) if args.command=='verify' else create(args.source,args.capture,args.execution,args.collector,args.plugin,args.response_root,args.destination)
 except (OSError,ValueError,UnicodeError,KeyError) as error:p.exit(70,str(error)+'\n')
 print(json.dumps(result));return 0


if __name__=='__main__':raise SystemExit(main())
