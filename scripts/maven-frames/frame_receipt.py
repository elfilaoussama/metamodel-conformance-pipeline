"""Lifecycle validation only. A complete callback stream is not a source-consumption certificate."""
import re

class FrameAdmissionError(ValueError):pass

def completed_frames(rows,build_exit):
 if type(build_exit) is not int or build_exit!=0:raise FrameAdmissionError('native Maven build did not complete successfully')
 if not isinstance(rows,list) or not rows:raise FrameAdmissionError('native compiler frame stream is empty')
 active={};seen=set();completed=[]
 for row in rows:
  if not isinstance(row,dict) or row.get('format')!='native-maven-compiler-mojo-metadata-v1' or row.get('consumptionCertified') is not False:raise FrameAdmissionError('unsupported frame format/certification')
  frame=row.get('frame');phase=row.get('phase')
  if type(frame) is not int or frame<1:raise FrameAdmissionError('invalid frame identity')
  if any(not isinstance(row.get(key),str) or not row[key] or '\0' in row[key] for key in ('module','executionId')) or not isinstance(row.get('fields'),dict):raise FrameAdmissionError('native module/execution/configuration identity unavailable')
  if row.get('pluginVersion')!='3.16.0' or row.get('goal') not in ('compile','testCompile'):raise FrameAdmissionError('unsupported native compiler profile')
  expectedClass='org.apache.maven.plugin.compiler.'+('CompilerMojo' if row['goal']=='compile' else 'TestCompilerMojo')
  if row.get('mojoClass')!=expectedClass or not isinstance(row.get('mojoClassSha256'),str) or not re.fullmatch('[0-9a-f]{64}',row['mojoClassSha256']):raise FrameAdmissionError('native Mojo class identity unavailable')
  if phase=='BEFORE':
   if frame in seen:raise FrameAdmissionError('duplicate native frame start')
   seen.add(frame);active[frame]=row
  elif phase=='SUCCESS':
   before=active.pop(frame,None)
   if before is None:raise FrameAdmissionError('terminal frame lacks a start')
   for key in ('module','executionId','goal','pluginVersion','mojoClass','mojoClassSha256'):
    if before.get(key)!=row.get(key):raise FrameAdmissionError('terminal native frame identity changed')
   completed.append((before,row))
  elif phase=='FAILURE':raise FrameAdmissionError('native compiler frame failed')
  else:raise FrameAdmissionError('unknown native frame phase')
 if active or not completed:raise FrameAdmissionError('native compiler frame stream is incomplete')
 return completed

def read_frames(path,*,with_identity=False):
 import json,hashlib
 if path.is_symlink() or not path.is_file() or path.stat().st_size>64*1024*1024:raise FrameAdmissionError('frame artifact unavailable or exceeds64MiB policy')
 def unique(pairs):
  result={}
  for key,value in pairs:
   if key in result:raise FrameAdmissionError('duplicate JSON field')
   result[key]=value
  return result
 def invalid_constant(value):raise FrameAdmissionError('non-JSON numeric constant')
 rows=[];total=0;digest=hashlib.sha256()
 with path.open('rb') as stream:
  while True:
   encoded=stream.readline(8*1024*1024+1)
   if not encoded:break
   total+=len(encoded)
   digest.update(encoded)
   if len(encoded)>8*1024*1024 or len(rows)>=4096 or total>64*1024*1024:raise FrameAdmissionError('frame artifact row/count/bytes exceeds operational policy')
   if not encoded.endswith(b'\n'):raise FrameAdmissionError('native frame row is not terminated')
   line=encoded.decode('utf-8')
   if not line.strip():raise FrameAdmissionError('blank native frame row')
   rows.append(json.loads(line,object_pairs_hook=unique,parse_constant=invalid_constant))
 return (rows,dict(sha256=digest.hexdigest(),bytes=total)) if with_identity else rows

def main():
 import argparse,json
 from pathlib import Path
 parser=argparse.ArgumentParser(description='Validate pinned native Maven compiler callback lifecycles; this does not certify source/input consumption.')
 parser.add_argument('--frames',type=Path,required=True);parser.add_argument('--build-exit',type=int,required=True)
 args=parser.parse_args()
 try:frames=completed_frames(read_frames(args.frames),args.build_exit)
 except (FrameAdmissionError,OSError,UnicodeError,ValueError) as problem:parser.exit(70,str(problem)+'\n')
 print(json.dumps(dict(kind='VALIDATED_NATIVE_MOJO_LIFECYCLE_ONLY',frameCount=len(frames),consumptionCertified=False,sourceSelectionCertified=False)))
 return 0

if __name__=='__main__':raise SystemExit(main())
