#!/usr/bin/env python3
"""Build the opt-in pinned native-Mojo metadata collector. Never changes provider admission."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,shutil,subprocess

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if not re.fullmatch(r'[A-Za-z0-9._/:@-]+@sha256:[0-9a-f]{64}',a.image):p.error('immutable image digest required')
 destination=a.output.absolute()
 if any(c in str(destination) for c in ',\n\r\t'):p.error('unsupported output mount path')
 destination.mkdir(parents=True,exist_ok=False);destination=destination.resolve(strict=True)
 here=Path(__file__).resolve().parent;source=here/'CompilerFrames.java';sources=[source,here/'CompilerBoundary.java',here/'SourceMonitor.java',here/'RetainedInputs.java',here/'NativeSourceTrace.java',here/'CompilerToolchain.java']
 native=here.parents[1]/'src/main/resources/java-native'
 sources.extend(native/name for name in ('NativeSemanticWorld.java','NativeTaskSemanticCapture.java','ConsumedSourceFileManager.java','ReturnedBufferStore.java','RouteTrace.java'))
 for item in sources:shutil.copyfile(item,destination/item.name)
 spec=importlib.util.spec_from_file_location('provider_execution',here.parent/'provider-execution.py');execution=importlib.util.module_from_spec(spec);spec.loader.exec_module(execution)
 sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
 identity=subprocess.run(['docker','image','inspect',a.image,'--format','{{.Id}}'],capture_output=True,text=True,timeout=20)
 if identity.returncode or not re.fullmatch(r'sha256:[0-9a-f]{64}',identity.stdout.strip()):p.exit(69,'declared image must be available locally\n')
 image=identity.stdout.strip()
 base=['docker','run','--rm','--read-only','--network=none','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,nosuid,nodev,noexec','--memory','768m','--pids-limit','128','--entrypoint','bash','--mount','type=bind,src='+str(destination)+',dst=/profile']
 script='''set -eu
mkdir -p /profile/classes/META-INF/sisu
javac --release 17 -cp "/usr/share/maven/lib/*" -d /profile/classes /profile/CompilerFrames.java /profile/CompilerBoundary.java /profile/SourceMonitor.java /profile/RetainedInputs.java /profile/CompilerToolchain.java
# The PARSE plugin runs inside the selected javac, including actual JDK8.
# com.sun.source is a tools API, so it cannot be compiled against the Java8
# --release symbol archive. Separate actual8 controls qualify API compatibility.
javac -source 8 -target 8 -d /profile/source-classes /profile/NativeSourceTrace.java /profile/NativeSemanticWorld.java /profile/NativeTaskSemanticCapture.java /profile/ConsumedSourceFileManager.java /profile/ReturnedBufferStore.java /profile/RouteTrace.java
mkdir -p /profile/source-classes/META-INF/services
printf "%s\\n" org.metamodel.mavenframes.NativeSourceTrace > /profile/source-classes/META-INF/services/com.sun.source.util.Plugin
printf "%s\\n" org.metamodel.nativeworld.NativeTaskSemanticCapture >> /profile/source-classes/META-INF/services/com.sun.source.util.Plugin
jar cf /profile/source-trace.jar -C /profile/source-classes .
java -cp "/profile/classes:/usr/share/maven/lib/*" org.metamodel.mavenframes.CompilerFrames
printf "%s\\n" org.metamodel.mavenframes.CompilerFrames > /profile/classes/META-INF/sisu/javax.inject.Named
jar cf /profile/compiler-frames.jar -C /profile/classes .
'''
 row=execution.run_stage(base,image,['-c',script],destination/'build.log',60)
 argv=row['command'];cleanup=execution._remove_container(argv[argv.index('--name')+1],15)
 receipt=dict(format='native-maven-compiler-metadata-profile-v1',status='FAILED',image=image,requestedImage=a.image,sourceSha256=sha(source),sourceHashes={p.name:sha(destination/p.name) for p in sources},stage=row,cleanup=cleanup,consumptionCertified=False,sourceSelectionCertified=False)
 if row['exit']==0 and cleanup['status']=='ABSENT':
  if any((destination/p.name).read_bytes()!=p.read_bytes() for p in sources):raise ValueError('collector source changed while building')
  receipt.update(status='BUILT_METADATA_COLLECTOR_ONLY',collectorJarSha256=sha(destination/'compiler-frames.jar'),sourcePluginJarSha256=sha(destination/'source-trace.jar'),sourcePluginBytecodeTarget=8)
 (destination/'profile.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
 return 0 if receipt['status']=='BUILT_METADATA_COLLECTOR_ONLY' else 1

if __name__=='__main__':raise SystemExit(main())
