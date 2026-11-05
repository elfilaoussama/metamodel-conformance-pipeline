"""Strict UTF8 quoted-line transport used by Plexus javac2.17.0.

This is not a general javac/shell lexer. An argument-file snapshot is reported
input evidence, not proof that these bytes were consumed by a compiler.
"""
import hashlib,os,stat
from pathlib import Path
from frame_receipt import FrameAdmissionError

FORMAT='plexus-javac-2.17.0-quoted-lines-v1'
# Immutable identity of the qualified2.17.0 bridge loaded by compiler-plugin3.16.0.
BRIDGE_CLASS_SHA256='0957a6ae659dc6bfbca8ffe7f6cac5e73282112e52175287d3095cd0acf717c0'
MAX_BYTES=1024*1024
ESCAPES={'\\':'\\','"':'"','n':'\n','r':'\r','t':'\t','f':'\f'}


def decode_plexus_arguments(data):
 if not isinstance(data,bytes) or not data or len(data)>MAX_BYTES:raise FrameAdmissionError('bounded nonempty javac response bytes required')
 try:text=data.decode('utf-8',errors='strict')
 except UnicodeError as error:raise FrameAdmissionError('UTF8 javac response profile required') from error
 if '\0' in text or not text.endswith('\n'):raise FrameAdmissionError('incomplete or NUL response transport')
 lines=text[:-1].split('\n')
 if len(lines)>65536:raise FrameAdmissionError('javac response argument-count policy exceeded')
 arguments=[]
 for line in lines:
  if line.endswith('\r'):line=line[:-1]
  if len(line)<2 or line[0]!='"' or line[-1]!='"':raise FrameAdmissionError('one fully quoted argument per response line required')
  value=[];i=1;end=len(line)-1
  while i<end:
   character=line[i];i+=1
   if character=='\\':
    if i>=end or line[i] not in ESCAPES:raise FrameAdmissionError('unqualified response-file escape')
    character=ESCAPES[line[i]];i+=1
   elif character in ('"','\r','\t','\f'):raise FrameAdmissionError('unescaped response-file quote/control')
   value.append(character)
  value=''.join(value)
  if value.startswith('@'):raise FrameAdmissionError('nested response expansion unqualified')
  arguments.append(value)
 return arguments


def read_response(path,max_bytes=MAX_BYTES):
 """Read and identify one stable regular unaliased bridge-owned response file."""
 path=Path(path)
 if type(max_bytes) is not int or not 0<max_bytes<=MAX_BYTES:raise FrameAdmissionError('invalid response-file byte bound')
 if not path.is_absolute() or path!=Path(os.path.normpath(path)) or any(p.is_symlink() for p in (path,*path.parents)):raise FrameAdmissionError('absolute unaliased response file required')
 before=path.stat()
 if not stat.S_ISREG(before.st_mode) or not 0<before.st_size<=max_bytes:raise FrameAdmissionError('response file kind/size unqualified')
 with path.open('rb') as stream:data=stream.read(max_bytes+1)
 after=path.stat()
 identity=lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
 if len(data)!=before.st_size or identity(before)!=identity(after) or any(p.is_symlink() for p in (path,*path.parents)):raise FrameAdmissionError('response file changed during capture')
 return dict(format=FORMAT,path=str(path),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),arguments=decode_plexus_arguments(data),consumptionCertified=False)


def bind_fork_responses(paths,compiler_rows,validated):
 """Join response snapshots to already validated runtime/PARSE receipts.

 The unique monitor token binds the bridge-created file to an invocation.
 This does not upgrade snapshots to consumed bytes or canonical admission.
 Callers must retain the files and the successful execution envelope separately.
 """
 if validated.get('kind')!='VALIDATED_NATIVE_JAVAC_PARSED_POPULATION':raise FrameAdmissionError('validated native parsed population required')
 populations={item['invocation']:item for item in validated['parsedInvocations']}
 calls=[row for row in compiler_rows if row.get('phase')=='RETURN']
 if len(calls)!=len(populations) or {row['invocation'] for row in calls}!=set(populations):raise FrameAdmissionError('fork response invocation population differs')
 snapshots=[read_response(path) for path in paths]
 if len(snapshots)!=len(calls) or len({item['path'] for item in snapshots})!=len(snapshots):raise FrameAdmissionError('exact distinct fork response population required')
 results=[];used=set()
 for call in calls:
  if call.get('compilerClass')!='org.codehaus.plexus.compiler.javac.JavacCompiler' or call.get('compilerClassSha256')!=BRIDGE_CLASS_SHA256:raise FrameAdmissionError('qualified Plexus javac bridge byte identity required')
  config=call['configuration'];monitor=call.get('sourceMonitor',{});population=populations[call['invocation']]
  if config.get('isFork') is not True or population.get('runtimeObserved') is not True or population.get('state')!='NATIVE_PARSED_POPULATION':raise FrameAdmissionError('observed completed fork runtime required')
  token=monitor.get('addedCompilerArgument')
  if not isinstance(token,str) or not token.startswith('-Xplugin:ConformanceSourceTrace '):raise FrameAdmissionError('unique monitored fork token required')
  matches=[item for item in snapshots if item['arguments'].count(token)==1]
  if len(matches)!=1 or matches[0]['path'] in used:raise FrameAdmissionError('fork response token correspondence unavailable or ambiguous')
  snapshot=matches[0];arguments=snapshot['arguments'];used.add(snapshot['path'])
  operands=config['getSourceFiles']
  if any(arguments.count(source)!=1 for source in operands):raise FrameAdmissionError('selected source operands differ from fork response')
  options=population.get('effectiveOptions')
  if options is not None:
   for name,value in options.items():
    if name.startswith('-proc:'):
     if (name in arguments)!=(value is not None):raise FrameAdmissionError('native processing option differs from fork response')
    elif value is not None:
     if arguments.count(name)!=1 or arguments.index(name)+1>=len(arguments) or arguments[arguments.index(name)+1]!=value:raise FrameAdmissionError('native effective option differs from fork response')
  results.append(dict(invocation=call['invocation'],frame=call['frame'],response=snapshot,
    actualExecutable=population['compilerToolchain']['executable'],workingDirectory=config['getWorkingDirectory'],
    canonicalAdmissionCertified=False))
 return results
