"""Verify retained before-call resolution trees and post-PARSE source files."""
from pathlib import PurePosixPath
import hashlib,re,stat
from frame_receipt import FrameAdmissionError,read_frames
from compiler_receipt import _sha


def _relative(value,dot=False):
 if not isinstance(value,str) or not value or '\0' in value:raise FrameAdmissionError('invalid retained relative path')
 if value=='.' and dot:return value
 path=PurePosixPath(value)
 if path.is_absolute() or any(p in ('.','..') for p in value.split('/')) or path.as_posix()!=value:raise FrameAdmissionError('noncanonical retained relative path')
 return value


def _safe(root,value,dot=False):
 value=_relative(value,dot);path=root
 if root.is_symlink() or not root.is_dir():raise FrameAdmissionError('regular retained root required')
 for part in (() if value=='.' else value.split('/')):
  path=path/part
  if path.is_symlink():raise FrameAdmissionError('symlink retained data rejected')
 return path


def _metadata(path,node):
 if type(node.get('lastModifiedNanos')) is not int or not isinstance(node.get('permissions'),str) or not re.fullmatch('[r-][w-][x-][r-][w-][x-][r-][w-][x-]',node['permissions']):raise FrameAdmissionError('retained metadata unavailable')
 actual=path.stat()
 if actual.st_mtime_ns!=node['lastModifiedNanos'] or stat.filemode(actual.st_mode)[1:]!=node['permissions']:raise FrameAdmissionError('retained file metadata changed')


def _file(path,node):
 if path.is_symlink() or not path.is_file() or not _sha(node.get('sha256')) or type(node.get('bytes')) is not int or node['bytes']<0 or node['bytes']>64*1024*1024:raise FrameAdmissionError('retained file identity unavailable')
 digest=hashlib.sha256();size=0
 with path.open('rb') as stream:
  while chunk:=stream.read(8192):
   size+=len(chunk)
   if size>64*1024*1024:raise FrameAdmissionError('retained file exceeds byte policy')
   digest.update(chunk)
 if size!=node['bytes'] or digest.hexdigest()!=node['sha256']:raise FrameAdmissionError('retained file bytes changed')
 _metadata(path,node);return size


def validate_retained_inputs(root,descriptor,units,configuration):
 if not isinstance(descriptor,dict) or set(descriptor)!={'format','root','manifest','snapshotPoint','consumptionCertified'} or descriptor.get('format')!='native-compiler-retained-inputs-v1' or descriptor.get('snapshotPoint')!='BEFORE_COMPILER_CALL' or descriptor.get('consumptionCertified') is not False:raise FrameAdmissionError('unsupported retained input descriptor')
 bound=descriptor['manifest']
 if not isinstance(bound,dict) or set(bound)!={'sha256','bytes'} or not _sha(bound.get('sha256')) or type(bound.get('bytes')) is not int or bound['bytes']<0:raise FrameAdmissionError('invalid retained manifest identity')
 manifest_path=_safe(root,'manifest.jsonl');rows,identity=read_frames(manifest_path,with_identity=True)
 if identity!=descriptor.get('manifest') or len(rows)!=1:raise FrameAdmissionError('retained manifest identity mismatch')
 manifest=rows[0]
 if not isinstance(manifest,dict) or any(manifest.get(key)!=descriptor[key] for key in ('format','snapshotPoint','consumptionCertified')):raise FrameAdmissionError('retained manifest profile mismatch')
 if manifest.get('sourceEncoding')!=configuration.get('getSourceEncoding') or (manifest.get('sourceEncoding') is not None and not isinstance(manifest['sourceEncoding'],str)) or not isinstance(manifest.get('nativeDefaultCharset'),str):raise FrameAdmissionError('native source encoding identity unavailable')
 expected={'manifest.jsonl','resolution','sources'};by_snapshot={};role_outputs={};total=0;nodes=0
 for role,key in [('classpath','getClasspathEntries'),('processorPath','getProcessorPathEntries')]:
  entries=manifest.get(role);original=configuration.get(key)
  if role=='processorPath' and original is None:
   if entries is not None:raise FrameAdmissionError('default processor discovery changed')
   role_outputs[role]=None;continue
  if not isinstance(entries,list) or any(not isinstance(entry,dict) for entry in entries) or [entry.get('original') for entry in entries]!=original:raise FrameAdmissionError('retained resolution order changed')
  role_outputs[role]=entries
  for entry in entries:
   relative=_relative(entry.get('snapshot'));parts=PurePosixPath(relative).parts
   if len(parts)!=3 or parts[0]!='resolution' or not re.fullmatch('entry-[0-9]+',parts[1]):raise FrameAdmissionError('unexpected resolution snapshot location')
   if relative in by_snapshot:
    if by_snapshot[relative]!=entry:raise FrameAdmissionError('conflicting repeated resolution snapshot')
    continue
   by_snapshot[relative]=entry;expected.update((relative,'resolution/'+parts[1]));target=_safe(root,relative);inventory=entry.get('inventory');kind=entry.get('kind')
   if not isinstance(inventory,list):raise FrameAdmissionError('retained resolution inventory unavailable')
   if kind=='ABSENT':
    if inventory or not target.is_dir() or list(target.iterdir()):raise FrameAdmissionError('absent input view must be an explicit empty directory')
    continue
   if kind not in ('FILE','DIRECTORY') or not inventory or not any(item.get('path')=='.' and item.get('kind')==kind for item in inventory if isinstance(item,dict)):raise FrameAdmissionError('retained resolution root kind mismatch')
   seen=set()
   for node in inventory:
    if not isinstance(node,dict):raise FrameAdmissionError('invalid retained node')
    name=_relative(node.get('path'),dot=True)
    if name in seen:raise FrameAdmissionError('duplicate retained node')
    seen.add(name);location=target if name=='.' else _safe(target,name);expected.add(relative if name=='.' else relative+'/'+name);nodes+=1
    if nodes>32768:raise FrameAdmissionError('retained node policy exceeded')
    if node.get('kind')=='FILE':total+=_file(location,node)
    elif node.get('kind')=='DIRECTORY' and location.is_dir() and not location.is_symlink():_metadata(location,node)
    else:raise FrameAdmissionError('unsupported retained node kind')
    if total>512*1024*1024:raise FrameAdmissionError('retained resolution byte policy exceeded')
 source_total=0;sources=[];encoding=manifest.get('sourceEncoding') or manifest['nativeDefaultCharset']
 if not isinstance(encoding,str):raise FrameAdmissionError('native source encoding unavailable')
 for index,unit in enumerate(units,1):
  raw=unit.get('rawSourceSnapshot')
  if not isinstance(raw,dict) or raw.get('point')!='POST_PARSE_FILE_SNAPSHOT' or raw.get('path')!='sources/source-'+str(index)+'.bin':raise FrameAdmissionError('native source snapshot binding missing')
  source_path=_safe(root,raw['path']);source_total+=_file(source_path,raw)
  if source_total>512*1024*1024:raise FrameAdmissionError('retained source byte policy exceeded')
  text=source_path.read_bytes().decode(encoding,errors='strict')
  if hashlib.sha256(text.encode('utf-8')).hexdigest()!=unit['reportedSourceCharSha256']:raise FrameAdmissionError('retained source differs from reported native characters')
  expected.add(raw['path']);sources.append(dict(uri=unit['uri'],snapshot=raw,encoding=encoding))
 actual={p.relative_to(root).as_posix() for p in root.rglob('*')}
 if actual!=expected or any(p.is_symlink() for p in root.rglob('*')):raise FrameAdmissionError('unbound or missing retained inputs')
 return dict(snapshotPoint='BEFORE_COMPILER_CALL',resolution=role_outputs,sources=sources,consumptionCertified=False)
