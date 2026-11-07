"""Validate sealed native compiler API traces. Does not admit canonical source evidence."""
from pathlib import PurePosixPath
import re
from frame_receipt import FrameAdmissionError,completed_frames,read_frames


def _sha(value):
 return isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value)


def _path(value):
 return isinstance(value,str) and bool(value) and not any(c in value for c in '\0\r\n\t') and PurePosixPath(value).is_absolute() and '..' not in PurePosixPath(value).parts


def validate_attempt(metadata,compiler,seal,attempt,build_exit):
 """Inputs include one-pass byte identities from read_frames, not recomputed JSON."""
 if not isinstance(attempt,str) or not re.fullmatch('[0-9a-f]{32}',attempt):raise FrameAdmissionError('invalid expected native attempt')
 if not isinstance(seal,dict) or seal.get('format')!='native-maven-compiler-session-v1' or seal.get('attempt')!=attempt:raise FrameAdmissionError('native session seal identity unavailable')
 if seal.get('nativeSuccess') is not True or type(seal.get('activeFrameCount')) is not int or seal['activeFrameCount']!=0:raise FrameAdmissionError('native session failed or contains unfinished frames')
 for name,artifact in [('metadata',metadata),('compiler',compiler)]:
  bound=seal.get(name)
  if not isinstance(bound,dict) or set(bound)!=set(('sha256','bytes')) or type(bound.get('bytes')) is not int or bound['bytes']<0 or not _sha(bound.get('sha256')) or bound!=artifact[1]:raise FrameAdmissionError('native '+name+' artifact identity mismatch')
 pairs=completed_frames(metadata[0],build_exit)
 count=seal.get('frameCount');calls=seal.get('invocationCount')
 if type(count) is not int or count<1 or count!=len(pairs) or type(calls) is not int or calls<0 or calls*2!=len(compiler[0]):raise FrameAdmissionError('invalid native session counts')
 frames={before['frame']:(before,after) for before,after in pairs}
 if sorted(frames)!=list(range(1,count+1)):raise FrameAdmissionError('native session frame census mismatch')
 if any(row.get('attempt')!=attempt for row in metadata[0]):raise FrameAdmissionError('mixed native metadata attempts')
 active={};seen=set();completed=[];per_frame={key:[] for key in frames}
 for row in compiler[0]:
  if not isinstance(row,dict) or row.get('format')!='native-compiler-api-boundary-v1' or row.get('attempt')!=attempt or row.get('consumptionCertified') is not False:raise FrameAdmissionError('compiler trace profile/attempt mismatch')
  frame=row.get('frame');invocation=row.get('invocation');phase=row.get('phase')
  if type(frame) is not int or frame not in frames or type(invocation) is not int or invocation<1:raise FrameAdmissionError('unbound compiler invocation')
  if not isinstance(row.get('compilerClass'),str) or not row['compilerClass'] or not _sha(row.get('compilerClassSha256')):raise FrameAdmissionError('compiler identity unavailable')
  config=row.get('configuration');sources=row.get('selectedSourceHashes')
  if not isinstance(config,dict) or not isinstance(sources,list):raise FrameAdmissionError('compiler configuration/source census unavailable')
  for key in ('getSourceFiles','getSourceLocations','getClasspathEntries','getModulepathEntries'):
   if not isinstance(config.get(key),list) or any(not _path(p) for p in config[key]):raise FrameAdmissionError('unsupported native compiler path selection')
  if len(set(config['getSourceFiles']))!=len(config['getSourceFiles']):raise FrameAdmissionError('duplicate selected compiler source')
  if any(not isinstance(item,dict) or not _path(item.get('path')) or not _sha(item.get('sha256')) for item in sources) or [item['path'] for item in sources]!=config['getSourceFiles']:raise FrameAdmissionError('selected source/hash census mismatch')
  if phase=='START':
   if invocation in seen or row.get('compilerSuccess') is not None or row.get('throwableClass') is not None:raise FrameAdmissionError('invalid/duplicate compiler start')
   seen.add(invocation);active[invocation]=row
  elif phase=='RETURN':
   before=active.pop(invocation,None)
   if before is None:raise FrameAdmissionError('compiler terminal lacks start')
   for key in ('frame','compilerClass','compilerClassSha256','configuration','selectedSourceHashes'):
    if before[key]!=row[key]:raise FrameAdmissionError('compiler identity/configuration/snapshot changed')
   if row.get('compilerSuccess') is not True or row.get('throwableClass') is not None:raise FrameAdmissionError('compiler reported failure despite native lifecycle success')
   if frames[frame][1]['fields'].get('skipCompilation') is not False:raise FrameAdmissionError('compiler invocation contradicts skip metadata')
   completed.append((before,row));per_frame[frame].append(before)
  else:raise FrameAdmissionError('compiler failed or phase unsupported')
 if active or sorted(seen)!=list(range(1,calls+1)) or len(completed)!=calls:raise FrameAdmissionError('native compiler invocation census incomplete')
 summaries=[]
 for frame,(before,after) in frames.items():
  invocations=per_frame[frame];selected=[item for call in invocations for item in call['selectedSourceHashes']]
  summaries.append(dict(frame=frame,executionId=before['executionId'],invocationCount=len(invocations),state='NO_COMPILER_CALL' if not invocations else ('EMPTY_SOURCE_CALL' if not selected else 'SELECTED_SOURCE_COMPILATION'),selectedSourceHashes=selected))
 return dict(kind='VALIDATED_SEALED_COMPILER_API_ATTEMPT',attempt=attempt,frames=summaries,consumptionCertified=False,canonicalAdmissionCertified=False)


def main():
 import argparse,json
 from pathlib import Path
 parser=argparse.ArgumentParser(description=__doc__)
 for flag in ('metadata','compiler','session'):parser.add_argument('--'+flag,type=Path,required=True)
 parser.add_argument('--attempt',required=True);parser.add_argument('--build-exit',type=int,required=True);args=parser.parse_args()
 try:
  seals=read_frames(args.session)
  if len(seals)!=1:raise FrameAdmissionError('exactly one native session seal required')
  result=validate_attempt(read_frames(args.metadata,with_identity=True),read_frames(args.compiler,with_identity=True),seals[0],args.attempt,args.build_exit)
 except (FrameAdmissionError,OSError,UnicodeError,ValueError) as problem:parser.exit(70,str(problem)+'\n')
 print(json.dumps(result));return 0


if __name__=='__main__':raise SystemExit(main())
