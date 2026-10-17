package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.*;
import metamodel.conformance.pipeline.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Same-invocation compiler facts. Positive source definitions do not certify
 * absent external definitions or selected-program implementation/link closure. */
final class CppNativeImplementationReceipt {
    private CppNativeImplementationReceipt() { }

    record Result(boolean populationComplete, boolean bodiesComplete,
            Map<String,ClassifierAbstraction> abstractions,
            List<MethodBodyObservation> bodies, List<ImplementationBindingObservation> bindings,
            List<NativeEvidence.BodyBinding> nativeBindings, List<ObservationDiagnostic> diagnostics) {
        Result {
            abstractions=Map.copyOf(abstractions);bodies=List.copyOf(bodies);bindings=List.copyOf(bindings);
            nativeBindings=List.copyOf(nativeBindings);diagnostics=List.copyOf(diagnostics);
        }
        static Result unavailable() { return new Result(false,false,Map.of(),List.of(),List.of(),List.of(),List.of()); }
    }

    static Result observe(Path root, String context, String diagnosticPath, JsonNode receipt,
            Map<String,String> declarations, List<NativeEvidence.Input> inputs, Map<Path,byte[]> sourceBytes)
            throws IOException, ObservationException {
        if (receipt==null) return Result.unavailable(); // Older explicit helpers remain partial.
        try {
            require(text(receipt,"format").equals("cpp-implementation-facts-v1"),"unsupported implementation facts");
            require(text(receipt,"scope").equals("PROJECT_CLASS_STRUCT_WRITTEN_NON_SPECIAL_METHODS"),"unsupported method census scope");
            require(text(receipt,"projectRoot").equals(root.toString()),"implementation census root mismatch");
            boolean population=bool(receipt,"populationComplete"), complete=population;
            var abstraction=new HashMap<String,ClassifierAbstraction>();
            for (JsonNode row:array(receipt,"classes")) {
                String owner=declarations.get(text(row,"ownerAstId"));
                ClassifierAbstraction value=ClassifierAbstraction.valueOf(text(row,"abstraction"));
                if (owner==null) continue; // Outside the independently mapped classifier fragment.
                require(owner.startsWith("cls_"),"native class identity maps to a non-classifier");
                require(abstraction.putIfAbsent(owner,value)==null,"duplicate native class abstraction");
            }
            var methods=new HashMap<String,JsonNode>();var written=new HashSet<String>();
            for (JsonNode row:array(receipt,"methods")) {
                String id=text(row,"methodAstId");text(row,"canonicalMethodAstId");text(row,"ownerAstId");
                bool(row,"pure");bool(row,"deleted");bool(row,"defaulted");
                require(methods.putIfAbsent(id,row)==null,"duplicate native method declaration");
                if (bool(row,"hasWrittenBody")) written.add(id);
            }
            var bodies=new ArrayList<MethodBodyObservation>();var bindings=new ArrayList<ImplementationBindingObservation>();
            var nativeBindings=new ArrayList<NativeEvidence.BodyBinding>();var diagnostics=new ArrayList<ObservationDiagnostic>();
            Map<String,String> hashes=new HashMap<>();for (var input:inputs) hashes.put(input.path(),input.sha256());
            Set<Path> validated=new HashSet<>();Set<String> seenMethods=new HashSet<>(), seenBodies=new HashSet<>();
            for (JsonNode row:array(receipt,"bodies")) {
                String id=text(row,"methodAstId");JsonNode method=methods.get(id);
                require(written.contains(id) && method!=null && seenMethods.add(id),"body does not uniquely match a written definition");
                String canonical=text(row,"canonicalMethodAstId"), ownerId=text(row,"ownerAstId");
                require(canonical.equals(text(method,"canonicalMethodAstId")) && ownerId.equals(text(method,"ownerAstId")),
                        "body/declaration native identity disagreement");
                JsonNode span=row.path("span");String knowledge=text(span,"knowledge");
                NativeEvidence.Evidence<NativeEvidence.SourceSpan> nativeSpan;
                String bodyKey=null;
                if (knowledge.equals("KNOWN")) {
                    Path file=Path.of(text(span,"path"));
                    require(file.isAbsolute() && file.equals(file.normalize()) && file.startsWith(root)
                            && file.equals(file.toRealPath()),"body source is outside the canonical project root");
                    byte[] bytes=sourceBytes.get(file);
                    if (bytes==null) { bytes=Files.readAllBytes(file);sourceBytes.put(file,bytes); }
                    if (validated.add(file)) require(Hashing.sha256(bytes).equals(hashes.get(file.toString())),
                            "body source bytes differ from the native consumed buffer");
                    long begin=number(span,"startOffset"),end=number(span,"endOffsetExclusive");
                    require(begin>=0 && end>begin && end<=bytes.length,"body offsets outside native source buffer");
                    int first=line(span,"startLine"),last=line(span,"endLine");
                    require(last>=first,"invalid body source line range");
                    String relative=root.relativize(file).toString().replace('\\','/');
                    bodyKey="body_"+Hashing.sha256("cpp-body\0"+context+"\0"+relative+"\0"+begin+"\0"+end);
                    if (seenBodies.add(bodyKey)) bodies.add(new MethodBodyObservation(bodyKey,relative,first,last));
                    else complete=false; // One written physical occurrence cannot become two body atoms.
                    nativeSpan=NativeEvidence.Evidence.known(List.of(new NativeEvidence.SourceSpan(relative,first,last)));
                } else {
                    require(knowledge.equals("UNKNOWN"),"unsupported native body span knowledge");
                    String reason=text(span,"reason");complete=false;
                    nativeSpan=NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,reason);
                    diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,diagnosticPath,0,
                            "Compiler-observed C++ body has no admitted physical suite span: "+reason));
                }
                String owner=declarations.get(ownerId),target=declarations.get(canonical);
                if (owner!=null && target!=null && owner.startsWith("cls_") && target.startsWith("mem_")) {
                    nativeBindings.add(new NativeEvidence.BodyBinding(context,owner,target,nativeSpan,
                            NativeEvidence.Evidence.known(List.of(target))));
                    if (bodyKey!=null) bindings.add(new ImplementationBindingObservation(
                            "bind_"+Hashing.sha256("cpp-source-definition\0"+owner+"\0"+target+"\0"+bodyKey),owner,target,bodyKey));
                } else diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,diagnosticPath,0,
                        "Compiler-observed C++ source body retained without unique canonical declaration/owner mapping"));
            }
            require(seenMethods.equals(written),"written method definition/body population differs");
            return new Result(population,complete,abstraction,bodies,bindings,nativeBindings,diagnostics);
        } catch (IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ implementation census: "+invalid.getMessage(),invalid);
        }
    }

    private static JsonNode array(JsonNode value,String field) {
        JsonNode result=value.get(field);require(result!=null && result.isArray(),"missing/nonarray "+field);return result;
    }
    private static String text(JsonNode value,String field) {
        JsonNode result=value.get(field);require(result!=null && result.isTextual() && !result.asText().isEmpty(),"missing/nontextual "+field);return result.asText();
    }
    private static boolean bool(JsonNode value,String field) {
        JsonNode result=value.get(field);require(result!=null && result.isBoolean(),"missing/nonboolean "+field);return result.booleanValue();
    }
    private static long number(JsonNode value,String field) {
        JsonNode result=value.get(field);require(result!=null && result.isIntegralNumber() && result.canConvertToLong(),"invalid integral "+field);return result.longValue();
    }
    private static int line(JsonNode value,String field) {
        long result=number(value,field);require(result>=1 && result<=Integer.MAX_VALUE,"invalid source line "+field);return (int)result;
    }
    private static void require(boolean value,String message) { if (!value) throw new IllegalArgumentException(message); }
}
