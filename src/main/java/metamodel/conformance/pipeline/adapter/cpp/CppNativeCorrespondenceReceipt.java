package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.*;
import metamodel.conformance.pipeline.util.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Joins guarded native matches to independently observed original declarations
 * and source suites. No conformance rule or absence/completeness inference. */
final class CppNativeCorrespondenceReceipt {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    record Frame(Map<String,String> declarations,JsonNode originalFacts,CppNativeImplementationReceipt.Result facts) { }
    record Match(String sourceContext,String sourceMember,String targetContext,String targetMember,String usr) { }
    record Result(List<MethodBodyObservation> bodies,List<ImplementationBindingObservation> bindings,
            List<NativeEvidence.BodyBinding> nativeBindings,List<Match> matches,List<ObservationDiagnostic> diagnostics,String canonicalReceipt) {
        Result {bodies=List.copyOf(bodies);bindings=List.copyOf(bindings);nativeBindings=List.copyOf(nativeBindings);
            matches=List.copyOf(matches);diagnostics=List.copyOf(diagnostics);}
    }
    static Result project(Path root,String selectionId,CppNativeBatchReceipt.Result batch,Map<String,Frame> frames,
            Map<String,List<String>> owners) throws IOException,ObservationException {
        var bodies=new TreeMap<String,MethodBodyObservation>();var bindings=new ArrayList<ImplementationBindingObservation>();
        var nativeBindings=new ArrayList<NativeEvidence.BodyBinding>();var matches=new ArrayList<Match>();
        var diagnostics=new ArrayList<ObservationDiagnostic>();var receiptRows=new ArrayList<String>();
        for(var query:batch.queries()) {
            Frame receiver=frames.get(query.targetContextId()),origin=frames.get(query.sourceContextId());
            String target=receiver==null?null:receiver.declarations().get(query.targetCanonicalAstId());
            String targetOwner=receiver==null?null:receiver.declarations().get(query.targetOwnerAstId());
            String source=origin==null?null:origin.declarations().get(query.sourceCanonicalAstId());
            String sourceOwner=origin==null?null:origin.declarations().get(query.sourceOwnerAstId());
            Map<String,Object> row=new TreeMap<>();
            row.put("targetContext",query.targetContextId());row.put("targetMember",target==null?"":target);
            row.put("targetNativeIdentity",query.targetUsr());row.put("nativeStatus",query.status().name());
            row.put("nativeReason",query.reason());row.put("projectionStatus","NOT_A_FOREIGN_MATCH");
            if(query.status()==CppNativeBatchReceipt.Status.MATCHED) {
                row.put("sourceContext",query.sourceContextId());row.put("sourceMember",source==null?"":source);row.put("sourceNativeIdentity",query.sourceUsr());
                String unavailable=null;
                if(source==null || target==null || sourceOwner==null || targetOwner==null
                        || !owners.getOrDefault(source,List.of()).equals(List.of(sourceOwner))
                        || !owners.getOrDefault(target,List.of()).equals(List.of(targetOwner)))unavailable="UNMAPPED_DECLARATION";
                JsonNode definition=null;
                if(unavailable==null) {
                    require(origin.originalFacts()!=null && receiver.originalFacts()!=null,"matched native frame lacks original census");
                    for(JsonNode body:origin.originalFacts().path("bodies"))if(body.path("methodAstId").asText().equals(query.sourceDefinitionAstId())) {
                        require(definition==null,"duplicate original native definition");definition=body;
                    }
                    require(definition!=null && definition.path("canonicalMethodAstId").asText().equals(query.sourceCanonicalAstId())
                            && definition.path("ownerAstId").asText().equals(query.sourceOwnerAstId()),"match does not join its original native written definition");
                    boolean found=false;
                    for(JsonNode method:receiver.originalFacts().path("methods"))if(method.path("canonicalMethodAstId").asText().equals(query.targetCanonicalAstId())) {
                        require(method.path("ownerAstId").asText().equals(query.targetOwnerAstId()) && !method.path("hasWrittenBody").asBoolean(true),
                                "foreign match overwrites an originally observed receiver definition");found=true;
                    }
                    require(found,"matched receiver is absent from original native census");
                    if(!definition.path("span").path("knowledge").asText().equals("KNOWN"))unavailable="UNADMITTED_SOURCE_SPAN";
                }
                if(unavailable!=null) {
                    row.put("projectionStatus",unavailable);
                    diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,"cpp-native-correspondence",0,
                            "Guarded C++ native correspondence not projected: "+unavailable+"; native identity="+query.targetUsr()));
                } else {
                    JsonNode span=definition.get("span");Path file=Path.of(span.path("path").asText());
                    require(file.isAbsolute() && file.startsWith(root),"foreign body outside measured source root");
                    String relative=root.relativize(file).toString().replace('\\','/');
                    long begin=span.path("startOffset").longValue(),end=span.path("endOffsetExclusive").longValue();
                    String sourceBody=bodyKey(query.sourceContextId(),relative,begin,end);
                    String sourceMember=source,sourceClassifier=sourceOwner;
                    require(origin.facts().bindings().stream().anyMatch(b->b.targetMemberKey().equals(sourceMember)
                            && b.implementerClassifierId().equals(sourceClassifier) && b.bodyKey().equals(sourceBody)),
                            "foreign body lacks its independently validated original binding");
                    int first=span.path("startLine").intValue(),last=span.path("endLine").intValue();
                    String body=bodyKey(query.targetContextId(),relative,begin,end);
                    bodies.putIfAbsent(body,new MethodBodyObservation(body,relative,first,last));
                    bindings.add(new ImplementationBindingObservation("bind_"+Hashing.sha256("cpp-imported-definition\0"+targetOwner+"\0"+target+"\0"+body),targetOwner,target,body));
                    nativeBindings.add(new NativeEvidence.BodyBinding(query.targetContextId(),targetOwner,target,
                            NativeEvidence.Evidence.known(List.of(new NativeEvidence.SourceSpan(relative,first,last))),NativeEvidence.Evidence.known(List.of(target))));
                    matches.add(new Match(query.sourceContextId(),source,query.targetContextId(),target,query.targetUsr()));
                    row.put("projectionStatus","QUALIFIED");row.put("bodyPath",relative);row.put("bodyStartOffset",begin);row.put("bodyEndOffsetExclusive",end);
                }
            }
            receiptRows.add(JSON.writeValueAsString(row));
        }
        Collections.sort(receiptRows);
        // Raw pointer IDs, scratch routes and runtime metrics have no semantic role.
        // Original tool/input identities remain in the domain receipts, outside
        // this payload, avoiding a receipt -> domain -> receipt hash cycle.
        String canonical=JSON.writeValueAsString(Map.of("format","cpp-qualified-correspondence-v1","selectionId",selectionId,
                "queries",receiptRows.stream().map(value->{try{return JSON.readTree(value);}catch(IOException impossible){throw new IllegalStateException(impossible);}}).toList()));
        return new Result(new ArrayList<>(bodies.values()),bindings,nativeBindings,matches,diagnostics,canonical);
    }
    private static String bodyKey(String context,String path,long begin,long end) {return "body_"+Hashing.sha256("cpp-body\0"+context+"\0"+path+"\0"+begin+"\0"+end);}
    private static void require(boolean valid,String message) throws ObservationException {if(!valid)throw new ObservationException("invalid C++ native correspondence projection: "+message);}
}
