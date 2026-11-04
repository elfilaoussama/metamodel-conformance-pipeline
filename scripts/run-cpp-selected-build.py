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


def execution_plan(source,manifest,jar,java_home,output,native_helper=None,native_batch_helper=None,native_membership_capture=None):
    if native_helper is not None and native_batch_helper is not None:
        raise ValueError('select one native transport explicitly')
    batch=native_batch_helper is not None
    if batch:native_helper=native_batch_helper
    source,manifest,jar,java_home,output=[p.resolve(strict=True) for p in (source,manifest,jar,java_home,output)]
    provenance=Path(str(manifest)+'.provenance.json')
    data=json.loads(provenance.read_text())
    if data.get('manifestSha256')!=sha(manifest):raise ValueError('manifest/provenance byte mismatch')
    rows=[line.split('\t',2) for line in manifest.read_text().splitlines()]
    contexts=[row for row in rows if row[0]=='context']
    world_mode=data.get('transportVersion')=='ordered-v7' or any(len(row)==3 and row[2]=='ordered-v7' for row in contexts)
    native_rows=[row for row in rows if row[0] in ('native-membership','native-world','native-invocation')]
    if world_mode:
        if data.get('transportVersion')!='ordered-v7' or not contexts or any(len(row)!=3 or row[2]!='ordered-v7' for row in contexts):
            raise ValueError('native world transport versions disagree')
        if not batch:raise ValueError('native worlds require retained batch helper')
        if native_membership_capture is None:raise ValueError('native worlds require explicit retained membership capture')
    elif native_rows or data.get('nativeMembership') is not None or data.get('nativeWorlds') is not None or native_membership_capture is not None:
        raise ValueError('native membership requires ordered-v7 transport')
    recipe=data.get('providerExecution',{})
    required={'recipeVersion','imageId','originalSourceRoot','cacheRoot','configuredSourceRoot','originalSourceMount','cacheMount'}
    captured=recipe.get('recipeVersion')==2
    if world_mode and not captured:raise ValueError('native worlds require captured provider recipe2')
    if set(recipe)!=(required|{'observationRoot'} if captured else required) or recipe['recipeVersion'] not in (1,2):raise ValueError('unsupported or missing provider execution recipe')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}',recipe['imageId']):raise ValueError('provider image must be an immutable image ID')
    if recipe['originalSourceRoot']!=str(source):raise ValueError('original source differs from provider recipe')
    cache=Path(str(manifest)+'.cache').resolve(strict=True)
    if recipe['cacheRoot']!=str(cache):raise ValueError('provider cache differs from execution recipe')
    if (recipe['configuredSourceRoot'],recipe['originalSourceMount'],recipe['cacheMount'])!=('/workspace/worktree','/workspace/source','/workspace/out'):
        raise ValueError('unsupported provider mount layout')
    configured=cache/'worktree'
    if not configured.is_dir() or not (java_home/'bin/java').is_file():raise ValueError('configured tree or Java runtime is unavailable')
    space_rows=[line.split('\t',2) for line in manifest.read_text().splitlines() if line.startswith('input-space\t')]
    captured_value=None
    if captured:
        if len(space_rows)!=1 or len(space_rows[0])!=3 or space_rows[0][1]!='cpp-captured-input-space-v1' or recipe['observationRoot']!='/workspace':
            raise ValueError('captured recipe requires its explicit input-space contract')
        def load(name,file):
            spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file))
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
        space_api=load('selected_cpp_space','cpp-input-space.py')
        digest_api=load('selected_cpp_tree','cpp-context-manifest.py')
        captured_value=json.loads(space_rows[0][2],object_pairs_hook=space_api.unique)
        if captured_value!=data.get('capturedInputSpace') or data.get('transportVersion') not in ('ordered-v6','ordered-v7'):
            raise ValueError('captured root manifest/provenance mismatch')
        physical={'/workspace/worktree':configured,'/workspace/out/build':cache/'build'}
        rows=captured_value.get('roots',[])
        if len(rows)!=2 or {row.get('path') for row in rows}!=set(physical):raise ValueError('unsupported provider root layout')
        spec=dict(format=space_api.FORMAT,observationRoot=recipe['observationRoot'],roots=[
            dict(id=row['id'],path=row['path'],kind=row['kind'],retainedPath=str(physical[row['path']])) for row in rows])
        checked=space_api.CapturedSpace(spec,digest_api.input_tree_digest)
        if checked.value!=captured_value:raise ValueError('retained captured trees changed')
    elif space_rows or data.get('capturedInputSpace') is not None:
        raise ValueError('captured roots require explicit provider recipe2')
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
    if captured and selection is None:raise ValueError('captured input space requires explicit configured target selection')
    native_binding=None
    if world_mode:
        membership_rows=[row for row in native_rows if row[0]=='native-membership']
        worlds=[row for row in native_rows if row[0]=='native-world']
        invocations=[row for row in native_rows if row[0]=='native-invocation']
        if len(membership_rows)!=1 or len(membership_rows[0])!=3 or membership_rows[0][1]!='cpp-native-membership-binding-v1':
            raise ValueError('native membership row missing or duplicated')
        if not worlds or any(len(row)!=3 or row[1]!='cpp-native-world-v1' for row in worlds) \
                or [json.loads(row[2]) for row in worlds]!=data.get('nativeWorlds'):
            raise ValueError('native worlds/provenance mismatch')
        context_ids=[row[1] for row in contexts]
        if len(set(context_ids))!=len(context_ids) or any(len(row)!=3 for row in invocations) \
                or sorted(row[1] for row in invocations)!=sorted(context_ids):
            raise ValueError('native invocation/context population mismatch')
        spec=importlib.util.spec_from_file_location('selected_native_membership',Path(__file__).with_name('cpp-native-membership-binding.py'))
        binder=importlib.util.module_from_spec(spec);spec.loader.exec_module(binder)
        native_binding=binder.bind_capture(native_membership_capture,cache/'configured-build-model.json',cache/'build/compile_commands.json')
        if native_binding!=json.loads(membership_rows[0][2]) or native_binding!=data.get('nativeMembership') \
                or native_binding['configuredModelCanonicalSha256']!=configured_model['semanticModelSha256']:
            raise ValueError('retained native membership/manifest/provenance mismatch')
    if native_helper is not None:
        native_helper=native_helper.resolve(strict=True)
        if not native_helper.is_file() or not os.access(native_helper,os.X_OK):
            raise ValueError('configured native helper must be an executable file')
    for path in (source,manifest,jar,java_home,output,cache)+((native_helper,) if native_helper is not None else ()):
        if ',' in str(path) or '\n' in str(path):raise ValueError('unsupported Docker mount path')
    mounts=[(source,'/workspace/source',True),(configured,'/workspace/worktree',True),
        (cache,'/workspace/out',True),(java_home,'/pipeline-java',True),
        (jar,'/pipeline.jar',True),(manifest,'/pipeline-contexts.tsv',True),(output,'/pipeline-output',False)]
    if captured:
        mounts=[row for row in mounts if row[1] not in ('/workspace/source','/workspace/out')]
        mounts.append((cache/'build','/workspace/out/build',True))
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
    if captured:identity['observationRoot']=recipe['observationRoot'];identity['capturedInputSpace']=captured_value
    if selection is not None:identity['configuredTargetSelection']=selection
    identity['nativeInputProfile']='RETAINED_BATCH_WORLD_V2' if world_mode else 'RETAINED_BATCH_V1' if batch else 'UNTRACED' if native_helper is None else 'SAME_PASS_INPUTS_V1'
    if native_binding is not None:
        identity['nativeMembership']=native_binding
        identity['nativeWorlds']=data['nativeWorlds']
        identity['nativeAdmissionValidatorSha256']={name:sha(Path(__file__).with_name(name)) for name in (
            'cpp-native-membership-binding.py','cpp-input-space.py','cpp-context-manifest.py')}
    if native_helper is not None:
        identity['nativeHelper']=dict(configuredPath=str(native_helper),containerPath='/pipeline-native-helper',sha256=sha(native_helper))
    return base,recipe['imageId'],identity


def analysis_java_arguments(identity):
    arguments=['/pipeline-java/bin/java','-Xmx1g']
    if identity['nativeInputProfile']=='SAME_PASS_INPUTS_V1':
        arguments.append('-Dmetamodel.conformance.cpp.nativeHelper=/pipeline-native-helper')
    elif identity['nativeInputProfile'] in ('RETAINED_BATCH_V1','RETAINED_BATCH_WORLD_V2'):
        arguments.append('-Dmetamodel.conformance.cpp.nativeBatchHelper=/pipeline-native-helper')
    return arguments+['-jar','/pipeline.jar','analyze',
        '--source',identity.get('observationRoot','/workspace/worktree'),'--language','cpp','--dependency-manifest','/pipeline-contexts.tsv',
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
    parser.add_argument('--native-membership-capture',type=Path,help='required raw retained Ninja capture for ordered-v7 admission; never inferred')
    parser.add_argument('--analysis-seconds',type=int,default=30)
    parser.add_argument('--replay-seconds',type=int,default=20)
    a=parser.parse_args()
    if a.analysis_seconds<=0 or a.replay_seconds<=0:parser.error('deadlines must be positive')
    a.output.mkdir(parents=True,exist_ok=True)
    for name in ['result','analysis.log','verification.log','container-start.log','selected-build-execution.json']:
        if (a.output/name).exists():parser.error('execution output must be fresh: '+name)
    started=time.monotonic();receipt=dict(state='FAILED',analysisExit=69,verificationExit=99,measurementCompleteness='NOT_CERTIFIED')
    try:
        base,image,identity=execution_plan(a.source,a.manifest,a.jar,a.java_home,a.output,a.native_helper,a.native_batch_helper,a.native_membership_capture)
        receipt.update(identity)
        execute_selected_stages(base,image,identity,a.output,a.analysis_seconds,a.replay_seconds,receipt)
    except Exception as failure:receipt['error']=repr(failure)
    receipt['secondsBeforeFinalReceiptWrite']=round(time.monotonic()-started,3)
    (a.output/'selected-build-execution.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt))
    return 0 if receipt['state']=='REPLAY_VERIFIED' else 1


if __name__=='__main__':raise SystemExit(main())
