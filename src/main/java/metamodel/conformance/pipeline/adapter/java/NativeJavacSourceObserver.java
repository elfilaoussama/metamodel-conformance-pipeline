package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.*;
import metamodel.conformance.pipeline.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Opt-in actual-compiler source-only observation; never falls back to host-derived facts. */
public final class NativeJavacSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID="native-javac-source";
    public static final String ADAPTER_VERSION="0.2.0";
    private final JavaNativeConfiguration configuration;
    public NativeJavacSourceObserver(JavaNativeConfiguration configuration){this.configuration=Objects.requireNonNull(configuration);}
    @Override public Observation observe(Path sourceRoot,Set<String> externalParents) throws ObservationException {
        try {
            Path root=SpoonJavaObserver.validateRoot(sourceRoot);
            List<Path> files=SpoonJavaObserver.discoverJavaFiles(root);
            try(var snapshot=SourceInputSnapshot.capture(root,files)) {
                List<SourceUnit> units=snapshot.units(Language.JAVA);
                Observation result;
                try {
                    if(units.isEmpty())throw new JavaNativeCompiler.CompilerFailure("no selected Java source files");
                    if(externalParents!=null && !externalParents.isEmpty())throw new JavaNativeCompiler.CompilerFailure("native source profile cannot turn external allowlist entries into compiler-observed declarations");
                    var captured=JavaNativeCompiler.capture(configuration,root,snapshot.root(),units);
                    result=JavaNativeProjection.project(configuration,root,captured);
                } catch(IOException | IllegalArgumentException failure) {
                    result=unknown(units,failure.getMessage());
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();throw new ObservationException("native Java observation interrupted",interrupted);
                }
                snapshot.verifyOriginalsUnchanged(SpoonJavaObserver.discoverJavaFiles(root));
                return result;
            }
        } catch(IOException failure){throw new ObservationException("native Java source capture/cleanup failed",failure);}
    }
    private static Observation unknown(List<SourceUnit> units,String reason) {
        String message="Selected native Java source world was not admitted: "+(reason==null?"unspecified native failure":reason);
        return new Observation("17",ADAPTER_ID,ADAPTER_VERSION,List.of(),Set.of(),units,List.of(),List.of(),List.of(),List.of(),List.of(),
                List.of(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,units.isEmpty()?"source-root":units.get(0).path(),0,message)),List.of());
    }
}
