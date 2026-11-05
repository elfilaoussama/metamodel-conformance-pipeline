"""Join actual-task source fact transport to validated PARSE/runtime receipts.

This guard checks transport/census, not full canonical TYPE-field admission.
The canonical reader must additionally validate every fact and source span.
"""
from urllib.parse import urlsplit,unquote
from compiler_receipt import _path,_sha
from frame_receipt import FrameAdmissionError

FORMAT='native-javac-build-source-world-v1'
TYPE_KEYS=set('kind binaryName typeKind modifiers packageName nestingKind writtenBases effectiveBases sourceCensus source directSupertypes declarations lookup overrides declaredFields lookupFields fieldHiding index'.split())


def source_path(uri):
 if not isinstance(uri,str):raise FrameAdmissionError('semantic source URI unavailable')
 parsed=urlsplit(uri);path=unquote(parsed.path,errors='strict')
 if parsed.scheme!='file' or parsed.netloc or parsed.query or parsed.fragment or not _path(path):raise FrameAdmissionError('semantic source URI unsupported')
 return path


def validate_semantic_transport(loaded,expected,units,attempt,invocation,toolchain):
 rows,identity=loaded
 if identity!=expected or not isinstance(rows,list) or len(rows)<2:raise FrameAdmissionError('semantic artifact identity/incomplete transport')
 if any(not isinstance(row,dict) or type(row.get('index')) is not int or row['index']!=i for i,row in enumerate(rows,1)):raise FrameAdmissionError('semantic record sequence differs')
 header=rows[0];terminal=rows[-1]
 keys=set('kind format attempt invocation javaVersion javaHome compilerTaskClass capturePoint completionStrategy parsedSources consumptionCertified binaryConsumptionCertified index'.split())
 strategy='PROCESS_SHUTDOWN_WITH_OBSERVED_EXIT_REQUIRED' if toolchain['feature']==8 else 'TASK_COMPILATION_EVENT'
 if set(header)!=keys or header.get('kind')!='HEADER' or header.get('format')!=FORMAT or header.get('attempt')!=attempt or type(header.get('invocation')) is not int or header['invocation']!=invocation:raise FrameAdmissionError('semantic task identity differs')
 if header['javaVersion']!=toolchain['javaVersion'] or header['javaHome']!=toolchain['runtimeHome'] or header['compilerTaskClass'] not in ('com.sun.tools.javac.api.BasicJavacTask','com.sun.tools.javac.api.JavacTaskImpl'):raise FrameAdmissionError('semantic actual compiler differs')
 if header['capturePoint']!='ANALYZE_FINISHED_BEFORE_GENERATION' or header['completionStrategy']!=strategy or header['consumptionCertified'] is not False or header['binaryConsumptionCertified'] is not False:raise FrameAdmissionError('semantic capture/certification profile differs')
 keys=set('kind attempt invocation completion parsedUnits sourceTypes canonicalAdmissionCertified index priorRowsSha256'.split())
 if set(terminal)!=keys or terminal.get('kind')!='COMPLETE' or terminal.get('attempt')!=attempt or type(terminal.get('invocation')) is not int or terminal['invocation']!=invocation or terminal['completion']!=strategy or terminal['canonicalAdmissionCertified'] is not False or not _sha(terminal['priorRowsSha256']):raise FrameAdmissionError('semantic completion differs')
 if type(terminal['parsedUnits']) is not int or terminal['parsedUnits']!=len(units) or type(terminal['sourceTypes']) is not int or terminal['sourceTypes']!=len(rows)-2:raise FrameAdmissionError('semantic completion population differs')
 sources=header['parsedSources'];observed={}
 if not isinstance(sources,list):raise FrameAdmissionError('semantic source population unavailable')
 for source in sources:
  if not isinstance(source,dict) or set(source)!={'uri','reportedSourceCharSha256','point'} or not _sha(source.get('reportedSourceCharSha256')) or source.get('point')!='PARSE_RETURNED_CHARACTER_SNAPSHOT_NOT_CONSUMED_BUFFER':raise FrameAdmissionError('semantic source character snapshot differs')
  path=source_path(source['uri'])
  if path in observed:raise FrameAdmissionError('duplicate semantic source URI')
  observed[path]=source['reportedSourceCharSha256']
 if observed!={unit['path']:unit['reportedSourceCharSha256'] for unit in units}:raise FrameAdmissionError('semantic/PARSE source characters differ')
 expected_spans={(unit['path'],decl['start'],decl['end']) for unit in units for decl in unit['declarations']}
 spans=set();names=set()
 for row in rows[1:-1]:
  if set(row)!=TYPE_KEYS or row.get('kind')!='TYPE' or row.get('typeKind') not in ('CLASS','INTERFACE','ENUM','ANNOTATION_TYPE') or not isinstance(row.get('binaryName'),str) or not row['binaryName'] or row['binaryName'] in names:raise FrameAdmissionError('semantic source TYPE transport differs')
  names.add(row['binaryName']);span=row['source']
  if not isinstance(span,dict) or span.get('knowledge')!='SOURCE_SPAN' or any(type(span.get(key)) is not int for key in ('start','end')):raise FrameAdmissionError('semantic source TYPE span unavailable')
  position=(source_path(span.get('uri')),span['start'],span['end'])
  if position in spans:raise FrameAdmissionError('ambiguous semantic source TYPE position')
  spans.add(position)
 if spans!=expected_spans:raise FrameAdmissionError('semantic source TYPE/PARSE census differs')
 return dict(format=FORMAT,artifact=identity,sourceTypes=len(names),transportAndCensusValidated=True,
   typeFactsValidated=False,canonicalAdmissionCertified=False)
