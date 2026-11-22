package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.*;

/** Compiler-owned ordered parameter types. Historical v1 receipts grant no new authority. */
final class CppNativeSignatureReceipt {
    private CppNativeSignatureReceipt() { }
    record Signature(String owner, NativeEvidence.Evidence<NativeEvidence.TypeToken> parameters) { }
    record Result(boolean populationComplete, Map<String,Signature> signatures) {
        Result { signatures=Map.copyOf(signatures); }
        static Result unavailable() { return new Result(false,Map.of()); }
    }
    static Result observe(JsonNode receipt,Map<String,String> declarations) throws ObservationException {
        if(receipt==null)return Result.unavailable();
        try {
            String format=text(receipt,"format",false);
            if(format.equals("cpp-method-semantics-v1"))return Result.unavailable();
            require(format.equals("cpp-method-semantics-v2"),"unsupported format");
            boolean complete=bool(receipt,"populationComplete");
            JsonNode rows=receipt.get("methods");require(rows!=null && rows.isArray(),"missing method array");
            Map<String,Signature> signatures=new HashMap<>();Set<String> occurrences=new HashSet<>();
            for(JsonNode row:rows) {
                String occurrence=text(row,"declarationAstId",false),canonical=text(row,"canonicalMethodAstId",false),
                        ownerId=text(row,"ownerAstId",false),knowledge=text(row,"signatureKnowledge",false),
                        reason=text(row,"signatureReason",true);
                require(occurrences.add(occurrence),"duplicate declaration occurrence");
                require(knowledge.equals("KNOWN") || knowledge.equals("UNKNOWN"),"invalid signature knowledge");
                boolean known=knowledge.equals("KNOWN"),variadic=bool(row,"signatureVariadic");
                JsonNode types=row.get("canonicalParameterTypes");require(types!=null && types.isArray(),"missing parameter array");
                require(known ? reason.isEmpty() && !variadic : !reason.isBlank() && types.isEmpty(),"signature knowledge/payload mismatch");
                List<NativeEvidence.TypeToken> parameters=new ArrayList<>();
                for(JsonNode type:types) {
                    require(type.isTextual() && !type.textValue().isBlank(),"invalid canonical parameter type");
                    parameters.add(new NativeEvidence.TypeToken(type.textValue(),NativeEvidence.TypeMeaning.COMPILER_TYPE));
                }
                String member=declarations.get(canonical),owner=declarations.get(ownerId);
                if(member==null || owner==null) {complete=false;continue;}
                require(member.startsWith("mem_") && owner.startsWith("cls_"),"invalid mapped declaration kind");
                String mappedOccurrence=declarations.get(occurrence);
                require(mappedOccurrence==null || mappedOccurrence.equals(member),"occurrence disagrees with canonical declaration");
                var value=new Signature(owner,known ? NativeEvidence.Evidence.known(parameters)
                        : NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,reason));
                var previous=signatures.putIfAbsent(member,value);
                require(previous==null || previous.equals(value),"conflicting native parameter redeclarations");
            }
            return new Result(complete,signatures);
        } catch(IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ native signature receipt: "+invalid.getMessage(),invalid);
        }
    }
    private static String text(JsonNode node,String field,boolean empty) {
        JsonNode value=node.get(field);require(value!=null && value.isTextual() && (empty || !value.textValue().isBlank()),"missing/nontextual "+field);return value.textValue();
    }
    private static boolean bool(JsonNode node,String field) {
        JsonNode value=node.get(field);require(value!=null && value.isBoolean(),"missing/nonboolean "+field);return value.booleanValue();
    }
    private static void require(boolean valid,String message) {if(!valid)throw new IllegalArgumentException(message);}
}
