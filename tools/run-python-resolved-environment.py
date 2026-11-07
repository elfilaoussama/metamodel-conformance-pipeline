#!/usr/bin/env python3
"""Source-only AST analysis in the actual resolved Python environment/image.

Caller supplies a compatible Linux Java home. Lock/distribution metadata is
not a certificate of imported type graphs or complete environment consumption.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET

executor_path=Path(__file__).with_name('provider-execution.py')
executor_spec=importlib.util.spec_from_file_location('provider_execution',executor_path)
executor=importlib.util.module_from_spec(executor_spec);executor_spec.loader.exec_module(executor)
run_stage=executor.run_stage

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):digest.update(block)
    return digest.hexdigest()

def execution_plan(source,manifest,jar,java_home,output):
    source,manifest,jar,java_home,output=[p.resolve(strict=True) for p in (source,manifest,jar,java_home,output)]
    provenance=Path(str(manifest)+'.provenance.json')
    data=json.loads(provenance.read_text())
    if data.get('format')!='python-declared-environment-v1' or data.get('consumptionCertified') is not False:
        raise ValueError('unsupported Python declared-environment provenance')
    if data.get('manifestSha256')!=sha(manifest):raise ValueError('manifest/provenance byte mismatch')
    if data.get('originalSourceRoot')!=str(source):raise ValueError('source differs from provider receipt')
    if data.get('lockSha256')!=sha(source/'uv.lock'):raise ValueError('lock differs from provider receipt')
    image=data.get('imageId','')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}',image):raise ValueError('provider image must be immutable')
    runtime=data.get('runtimeIdentity',{})
    if set(runtime)!={'executable','sha256','version','implementation'}:
        raise ValueError('missing or unsupported runtime identity')
    if not isinstance(runtime['executable'],str) or not runtime['executable'].startswith('/') or not re.fullmatch(r'[0-9a-f]{64}',runtime['sha256']):
        raise ValueError('invalid runtime executable receipt')
    cache=Path(str(manifest)+'.cache').resolve(strict=True)
    if not (cache/'env/pyvenv.cfg').is_file() or not (java_home/'bin/java').is_file():
        raise ValueError('resolved environment or Java runtime unavailable')
    mounts=[(source,'/workspace/source',True),(cache,'/workspace/out',True),
        (java_home,'/pipeline-java',True),(manifest,'/pipeline-contexts.tsv',True),
        (jar,'/pipeline.jar',True),(output,'/pipeline-output',False)]
    base=['docker','run','--rm','--read-only','--network=none','--cap-drop=ALL',
        '--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev','--memory','1536m',
        '--user',str(os.getuid())+':'+str(os.getgid())]
    for host,target,readonly in mounts:
        if ',' in str(host) or '\n' in str(host):raise ValueError('unsupported Docker mount path')
        base+=['--mount','type=bind,src='+str(host)+',dst='+target+(',readonly' if readonly else '')]
    return base,image,dict(observationDomain='SOURCE_ONLY',consumptionCertified=False,
        runtimeIdentity=runtime,manifestSha256=sha(manifest),provenanceSha256=sha(provenance),
        lockSha256=sha(source/'uv.lock'),producerJarSha256=sha(jar),javaExecutableSha256=sha(java_home/'bin/java'),
        controllerSha256=sha(Path(__file__)),executorSha256=sha(executor_path))

RUNTIME_PROBE='''import hashlib,json,pathlib,sys
p=pathlib.Path('/proc/self/exe').resolve(strict=True)
h=hashlib.sha256()
with p.open('rb') as stream:
 for block in iter(lambda:stream.read(1<<20),b''):h.update(block)
print(json.dumps(dict(executable=str(p),sha256=h.hexdigest(),version='.'.join(str(v) for v in sys.version_info[:3]),implementation=sys.implementation.name)))
'''

def verify_observed_runtime(result,expected):
    model=ET.parse(result/'observation.xmi').getroot()
    domains=model.findall('nativeEvidence/domains')
    images=model.findall("nativeEvidence/reportedInputs/items[@name='runtimeImage']/backingFiles/values")
    if not domains or any(d.get('mode')!='SOURCE_ONLY' for d in domains) or len(images)!=1:
        raise ValueError('actual Python observation lacks source-only/runtime receipts')
    if images[0].get('path')!=expected['executable'] or images[0].get('sha256')!=expected['sha256']:
        raise ValueError('actual Python observer runtime differs from provider receipt')
    capsule=json.loads((result/'verification-capsule.json').read_text())
    if not capsule.get('adapterVersion','').endswith('/python-'+expected['version']):
        raise ValueError('actual Python observer version differs from provider receipt')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['source','manifest','jar','java-home','output']:parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--analysis-seconds',type=int,default=30);parser.add_argument('--replay-seconds',type=int,default=20)
    a=parser.parse_args()
    if a.analysis_seconds<=0 or a.replay_seconds<=0:parser.error('deadlines must be positive')
    a.output.mkdir(parents=True,exist_ok=True)
    for name in ['result','runtime-check.log','analysis.log','verification.log','resolved-environment-execution.json']:
        if (a.output/name).exists():parser.error('execution output must be fresh: '+name)
    started=time.monotonic();receipt=dict(state='FAILED',analysisExit=69,verificationExit=99,observationDomain='SOURCE_ONLY',measurementCompleteness='NOT_CERTIFIED')
    try:
        base,image,identity=execution_plan(a.source,a.manifest,a.jar,a.java_home,a.output);receipt.update(identity);receipt['imageId']=image
        receipt['runtimeCheck']=run_stage(base,image,['/workspace/out/env/bin/python','-I','-S','-c',RUNTIME_PROBE],a.output/'runtime-check.log',10)
        if receipt['runtimeCheck']['exit']!=0:raise ValueError('resolved runtime preflight failed')
        actual=json.loads((a.output/'runtime-check.log').read_text())
        if actual!=identity['runtimeIdentity']:raise ValueError('resolved runtime differs from provider receipt')
        receipt['analysis']=run_stage(base,image,['/pipeline-java/bin/java','-Xmx1g','-Dmetamodel.conformance.python=/workspace/out/env/bin/python','-jar','/pipeline.jar','analyze','--source','/workspace/source','--language','python','--dependency-manifest','/pipeline-contexts.tsv','--output','/pipeline-output/result'],a.output/'analysis.log',a.analysis_seconds)
        receipt['analysisExit']=receipt['analysis']['exit'];capsule=a.output/'result/verification-capsule.json'
        if receipt['analysisExit'] in (0,2,3) and capsule.is_file():
            try:verify_observed_runtime(a.output/'result',actual)
            except Exception:
                capsule.rename(capsule.with_name('rejected-verification-capsule.json'));receipt['analysisExit']=69;raise
            receipt['replay']=run_stage(base,image,['/pipeline-java/bin/java','-Xmx1g','-jar','/pipeline.jar','verify-capsule','--capsule','/pipeline-output/result/verification-capsule.json'],a.output/'verification.log',a.replay_seconds)
            receipt['verificationExit']=receipt['replay']['exit']
            if receipt['verificationExit']==0:receipt['state']='REPLAY_VERIFIED'
    except Exception as failure:receipt['error']=repr(failure)
    receipt['secondsBeforeFinalReceiptWrite']=round(time.monotonic()-started,3)
    (a.output/'resolved-environment-execution.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
    return 0 if receipt['state']=='REPLAY_VERIFIED' else 1
if __name__=='__main__':raise SystemExit(main())
