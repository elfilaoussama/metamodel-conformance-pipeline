#!/usr/bin/env python3
"""Execute a resolved C++ measurement in its recorded provider layout/image.

The caller supplies a compatible Java home. This is execution orchestration,
not a source-role or completeness certificate. Java retains compiler/tree guards.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import uuid


def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):digest.update(block)
    return digest.hexdigest()


def execution_plan(source,manifest,jar,java_home,output,native_helper=None):
    source,manifest,jar,java_home,output=[p.resolve(strict=True) for p in (source,manifest,jar,java_home,output)]
    provenance=Path(str(manifest)+'.provenance.json')
    data=json.loads(provenance.read_text())
    if data.get('manifestSha256')!=sha(manifest):raise ValueError('manifest/provenance byte mismatch')
    recipe=data.get('providerExecution',{})
    required={'recipeVersion','imageId','originalSourceRoot','cacheRoot','configuredSourceRoot','originalSourceMount','cacheMount'}
    if set(recipe)!=required or recipe['recipeVersion']!=1:raise ValueError('unsupported or missing provider execution recipe')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}',recipe['imageId']):raise ValueError('provider image must be an immutable image ID')
    if recipe['originalSourceRoot']!=str(source):raise ValueError('original source differs from provider recipe')
    cache=Path(str(manifest)+'.cache').resolve(strict=True)
    if recipe['cacheRoot']!=str(cache):raise ValueError('provider cache differs from execution recipe')
    if (recipe['configuredSourceRoot'],recipe['originalSourceMount'],recipe['cacheMount'])!=('/workspace/worktree','/workspace/source','/workspace/out'):
        raise ValueError('unsupported provider mount layout')
    configured=cache/'worktree'
    if not configured.is_dir() or not (java_home/'bin/java').is_file():raise ValueError('configured tree or Java runtime is unavailable')
    if native_helper is not None:
        native_helper=native_helper.resolve(strict=True)
        if not native_helper.is_file() or not os.access(native_helper,os.X_OK):
            raise ValueError('configured native helper must be an executable file')
    for path in (source,manifest,jar,java_home,output,cache)+((native_helper,) if native_helper is not None else ()):
        if ',' in str(path) or '\n' in str(path):raise ValueError('unsupported Docker mount path')
    mounts=[(source,'/workspace/source',True),(configured,'/workspace/worktree',True),
        (cache,'/workspace/out',True),(java_home,'/pipeline-java',True),
        (jar,'/pipeline.jar',True),(manifest,'/pipeline-contexts.tsv',True),(output,'/pipeline-output',False)]
    if native_helper is not None:mounts.append((native_helper,'/pipeline-native-helper',True))
    base=['docker','run','--rm','--read-only','--network=none','--cap-drop=ALL',
        '--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev',
        '--memory','1536m','--user',str(os.getuid())+':'+str(os.getgid())]
    for host,target,readonly in mounts:
        base+=['--mount','type=bind,src='+str(host)+',dst='+target+(',readonly' if readonly else '')]
    identity=dict(recipe=recipe,manifestSha256=sha(manifest),
        producerJarSha256=sha(jar),javaExecutableSha256=sha(java_home/'bin/java'),
        configuredJavaHome=str(java_home),controllerSha256=sha(Path(__file__)))
    identity['nativeInputProfile']='UNTRACED' if native_helper is None else 'SAME_PASS_INPUTS_V1'
    if native_helper is not None:
        identity['nativeHelper']=dict(configuredPath=str(native_helper),containerPath='/pipeline-native-helper',sha256=sha(native_helper))
    return base,recipe['imageId'],identity


def analysis_java_arguments(identity):
    arguments=['/pipeline-java/bin/java','-Xmx1g']
    if identity['nativeInputProfile']=='SAME_PASS_INPUTS_V1':
        arguments.append('-Dmetamodel.conformance.cpp.nativeHelper=/pipeline-native-helper')
    return arguments+['-jar','/pipeline.jar','analyze',
        '--source','/workspace/worktree','--language','cpp','--dependency-manifest','/pipeline-contexts.tsv',
        '--output','/pipeline-output/result']


def run_stage(base,image,arguments,log,seconds):
    name='conformance-cpp-'+uuid.uuid4().hex
    command=base+['--name',name,image]+arguments
    before=time.monotonic()
    with log.open('x') as stream:
        process=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            # Terminating a Docker client alone does not terminate its container.
            try:
                subprocess.run(['docker','rm','--force',name],stdout=stream,stderr=subprocess.STDOUT,timeout=15,check=True)
            finally:
                if process.poll() is None:
                    try:os.killpg(process.pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                process.wait()
            code=124
    return dict(command=command,exit=code,seconds=round(time.monotonic()-before,3))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['source','manifest','jar','java-home','output']:parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--native-helper',type=Path,help='optional executable built for the recorded provider image; no fallback on failure')
    parser.add_argument('--analysis-seconds',type=int,default=30)
    parser.add_argument('--replay-seconds',type=int,default=20)
    a=parser.parse_args()
    if a.analysis_seconds<=0 or a.replay_seconds<=0:parser.error('deadlines must be positive')
    a.output.mkdir(parents=True,exist_ok=True)
    for name in ['result','analysis.log','verification.log','selected-build-execution.json']:
        if (a.output/name).exists():parser.error('execution output must be fresh: '+name)
    started=time.monotonic();receipt=dict(state='FAILED',analysisExit=69,verificationExit=99,measurementCompleteness='NOT_CERTIFIED')
    try:
        base,image,identity=execution_plan(a.source,a.manifest,a.jar,a.java_home,a.output,a.native_helper)
        receipt.update(identity)
        receipt['analysis']=run_stage(base,image,analysis_java_arguments(identity),a.output/'analysis.log',a.analysis_seconds)
        receipt['analysisExit']=receipt['analysis']['exit']
        capsule=a.output/'result/verification-capsule.json'
        if receipt['analysisExit'] in (0,2,3) and capsule.is_file():
            receipt['replay']=run_stage(base,image,['/pipeline-java/bin/java','-Xmx1g','-jar','/pipeline.jar','verify-capsule',
                '--capsule','/pipeline-output/result/verification-capsule.json'],a.output/'verification.log',a.replay_seconds)
            receipt['verificationExit']=receipt['replay']['exit']
            if receipt['verificationExit']==0:receipt['state']='REPLAY_VERIFIED'
    except Exception as failure:receipt['error']=repr(failure)
    receipt['secondsBeforeFinalReceiptWrite']=round(time.monotonic()-started,3)
    (a.output/'selected-build-execution.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt))
    return 0 if receipt['state']=='REPLAY_VERIFIED' else 1


if __name__=='__main__':raise SystemExit(main())
