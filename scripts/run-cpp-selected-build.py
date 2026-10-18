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
import importlib.util
import time
executor_path=Path(__file__).with_name('provider-execution.py')
executor_spec=importlib.util.spec_from_file_location('provider_execution',executor_path)
executor=importlib.util.module_from_spec(executor_spec);executor_spec.loader.exec_module(executor)


def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):digest.update(block)
    return digest.hexdigest()


def execution_plan(source,manifest,jar,java_home,output,native_helper=None,native_batch_helper=None):
    if native_helper is not None and native_batch_helper is not None:
        raise ValueError('select one native transport explicitly')
    batch=native_batch_helper is not None
    if batch:native_helper=native_batch_helper
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
    model_rows=[line.split('\t',2) for line in manifest.read_text().splitlines() if line.startswith('build-model\t')]
    configured_model=None
    if model_rows:
        if len(model_rows)!=1 or len(model_rows[0])!=3 or model_rows[0][1]!='cmake-codemodel-v2':
            raise ValueError('invalid configured build model row')
        model_text=model_rows[0][2];model_hash=hashlib.sha256(model_text.encode()).hexdigest()
        capture=data.get('configuredBuildCapture',{})
        if data.get('configuredBuildModel')!=json.loads(model_text) or capture.get('formatVersion')!=1 \
                or capture.get('semanticModelSha256')!=model_hash \
                or capture.get('meaning')!='CONFIGURED_TARGET_FACTS_NOT_LINKER_INPUT_CLOSURE':
            raise ValueError('configured build model/capture mismatch')
        inputs=capture.get('inputs')
        if not isinstance(inputs,list) or not inputs:raise ValueError('configured build capture inputs missing')
        reply=cache/'build/.cmake/api/v1/reply';seen=set()
        for row in inputs:
            name=row.get('path')
            if not isinstance(name,str) or Path(name).name!=name or '\\' in name or name in seen:
                raise ValueError('invalid configured build capture path')
            seen.add(name);path=reply/name
            if path.is_symlink() or path.resolve(strict=True).parent!=reply.resolve(strict=True) or sha(path)!=row.get('sha256'):
                raise ValueError('configured File API input changed or aliased')
        configured_model=dict(semanticModelSha256=model_hash,reportedInputs=len(inputs),meaning=capture['meaning'])
    elif data.get('configuredBuildModel') is not None or data.get('configuredBuildCapture') is not None:
        raise ValueError('configured build facts missing from manifest')
    selection_rows=[line.split('\t',2) for line in manifest.read_text().splitlines() if line.startswith('build-selection\t')]
    selection=None
    if selection_rows:
        if configured_model is None or len(selection_rows)!=1 or len(selection_rows[0])!=3 \
                or selection_rows[0][1]!='direct-targets-v1':
            raise ValueError('invalid configured target population row')
        selection=json.loads(selection_rows[0][2])
        if not isinstance(selection,dict) or set(selection)!={'configuration','targetIds'} \
                or not isinstance(selection['configuration'],str) or not isinstance(selection['targetIds'],list) \
                or not selection['targetIds'] or any(not isinstance(value,str) or not value for value in selection['targetIds']) \
                or len(set(selection['targetIds']))!=len(selection['targetIds']) \
                or selection!=data.get('configuredTargetSelection'):
            raise ValueError('configured target population/provenance mismatch')
    elif data.get('configuredTargetSelection') is not None:
        raise ValueError('configured target population missing from manifest')
    if batch and selection is None:raise ValueError('retained batch requires explicit configured target selection')
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
        configuredJavaHome=str(java_home),controllerSha256=sha(Path(__file__)),executorSha256=sha(executor_path))
    if configured_model is not None:identity['configuredBuildFacts']=configured_model
    if selection is not None:identity['configuredTargetSelection']=selection
    identity['nativeInputProfile']='RETAINED_BATCH_V1' if batch else 'UNTRACED' if native_helper is None else 'SAME_PASS_INPUTS_V1'
    if native_helper is not None:
        identity['nativeHelper']=dict(configuredPath=str(native_helper),containerPath='/pipeline-native-helper',sha256=sha(native_helper))
    return base,recipe['imageId'],identity


def analysis_java_arguments(identity):
    arguments=['/pipeline-java/bin/java','-Xmx1g']
    if identity['nativeInputProfile']=='SAME_PASS_INPUTS_V1':
        arguments.append('-Dmetamodel.conformance.cpp.nativeHelper=/pipeline-native-helper')
    elif identity['nativeInputProfile']=='RETAINED_BATCH_V1':
        arguments.append('-Dmetamodel.conformance.cpp.nativeBatchHelper=/pipeline-native-helper')
    return arguments+['-jar','/pipeline.jar','analyze',
        '--source','/workspace/worktree','--language','cpp','--dependency-manifest','/pipeline-contexts.tsv',
        '--output','/pipeline-output/result']


def execute_selected_stages(base,image,identity,output,analysis_seconds,replay_seconds,receipt):
    # Setup is charged to analysis; final cleanup is charged to replay. No extra
    # successful-job budget is introduced by retaining the container.
    session=executor.ContainerSession(base,image,analysis_seconds+replay_seconds+30)
    analysis_started=time.monotonic();replay_started=None
    receipt['executionMode']='OWNED_CONTAINER_FRESH_JVM_PER_STAGE_V1'
    try:
        receipt['containerSetup']=session.start(output/'container-start.log',analysis_seconds)
        if receipt['containerSetup']['exit']!=0:
            receipt['analysisExit']=receipt['containerSetup']['exit'];return
        remaining=analysis_seconds-(time.monotonic()-analysis_started)
        receipt['analysis']=session.run(analysis_java_arguments(identity),output/'analysis.log',remaining)
        receipt['analysis']['secondsIncludingSetup']=round(time.monotonic()-analysis_started,3)
        if time.monotonic()-analysis_started>analysis_seconds:
            receipt['analysis']['exit']=124;receipt['analysis']['timedOut']=True
        receipt['analysisExit']=receipt['analysis']['exit']
        capsule=output/'result/verification-capsule.json'
        if receipt['analysisExit'] in (0,2,3) and capsule.is_file():
            replay_started=time.monotonic()
            receipt['replay']=session.run(['/pipeline-java/bin/java','-Xmx1g','-jar','/pipeline.jar','verify-capsule',
                '--capsule','/pipeline-output/result/verification-capsule.json'],output/'verification.log',replay_seconds)
            receipt['verificationExit']=receipt['replay']['exit']
    finally:
        cleanup_budget=15
        if replay_started is not None and receipt.get('verificationExit')==0:
            cleanup_budget=min(15,max(.001,replay_seconds-(time.monotonic()-replay_started)))
        receipt['containerCleanup']=session.close(cleanup_budget)
        if replay_started is not None and 'replay' in receipt:
            elapsed=time.monotonic()-replay_started
            receipt['replay']['secondsIncludingCleanup']=round(elapsed,3)
            if elapsed>replay_seconds:
                receipt['verificationExit']=124;receipt['replay']['exit']=124;receipt['replay']['timedOut']=True
        if receipt.get('analysisExit') in (0,2,3) and receipt.get('verificationExit')==0 and receipt['containerCleanup']['status']=='ABSENT':
            receipt['state']='REPLAY_VERIFIED'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['source','manifest','jar','java-home','output']:parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--native-helper',type=Path,help='optional executable built for the recorded provider image; no fallback on failure')
    parser.add_argument('--native-batch-helper',type=Path,help='retained-AST worker; requires explicit target selection and excludes --native-helper')
    parser.add_argument('--analysis-seconds',type=int,default=30)
    parser.add_argument('--replay-seconds',type=int,default=20)
    a=parser.parse_args()
    if a.analysis_seconds<=0 or a.replay_seconds<=0:parser.error('deadlines must be positive')
    a.output.mkdir(parents=True,exist_ok=True)
    for name in ['result','analysis.log','verification.log','container-start.log','selected-build-execution.json']:
        if (a.output/name).exists():parser.error('execution output must be fresh: '+name)
    started=time.monotonic();receipt=dict(state='FAILED',analysisExit=69,verificationExit=99,measurementCompleteness='NOT_CERTIFIED')
    try:
        base,image,identity=execution_plan(a.source,a.manifest,a.jar,a.java_home,a.output,a.native_helper,a.native_batch_helper)
        receipt.update(identity)
        execute_selected_stages(base,image,identity,a.output,a.analysis_seconds,a.replay_seconds,receipt)
    except Exception as failure:receipt['error']=repr(failure)
    receipt['secondsBeforeFinalReceiptWrite']=round(time.monotonic()-started,3)
    (a.output/'selected-build-execution.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt))
    return 0 if receipt['state']=='REPLAY_VERIFIED' else 1


if __name__=='__main__':raise SystemExit(main())
