package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.*;

/** Maps compiler-owned override edges and canonical return types, without P0 derivation. */
final class CppNativeMethodReceipt {
    private CppNativeMethodReceipt() { }
    record Method(String owner, NativeEvidence.Evidence<String> overrides,
                  NativeEvidence.Evidence<NativeEvidence.TypeToken> returnType) { }
    record Result(boolean populationComplete, Map<String,Method> methods, Map<String,String> declarationAliases) {
        Result { methods=Map.copyOf(methods); declarationAliases=Map.copyOf(declarationAliases); }
        static Result unavailable() { return new Result(false,Map.of(),Map.of()); }
    }
    static Result observe(JsonNode receipt,Map<String,String> declarations) throws ObservationException {
        if(receipt==null)return Result.unavailable();
        try {
            require(Set.of("cpp-method-semantics-v1","cpp-method-semantics-v2").contains(text(receipt,"format")),"unsupported format");
            JsonNode complete=receipt.get("populationComplete");
            require(complete!=null && complete.isBoolean(),"missing population completeness");
            boolean population=complete.booleanValue();
            Map<String,Method> methods=new HashMap<>();Map<String,String> aliases=new HashMap<>();Set<String> seen=new HashSet<>();
            for(JsonNode row:array(receipt,"methods")) {
                require(seen.add(text(row,"declarationAstId")),"duplicate native method occurrence");
                String canonical=text(row,"canonicalMethodAstId"),ownerId=text(row,"ownerAstId");
                String overrideKnowledge=knowledge(row,"overrideKnowledge"),returnKnowledge=knowledge(row,"returnKnowledge");
                Set<String> direct=ids(row,"directOverrides"),closure=ids(row,"transitiveOverrides");
                require(closure.containsAll(direct) && !closure.contains(canonical),"invalid native override closure");
                require(overrideKnowledge.equals("KNOWN") || closure.isEmpty(),"unknown override has positive targets");
                JsonNode spelling=row.get("canonicalReturnType");
                require(spelling!=null && spelling.isTextual(),"missing return spelling");
                require(returnKnowledge.equals("KNOWN") ? !spelling.textValue().isBlank() : spelling.textValue().isEmpty(),"return knowledge/spelling mismatch");
                String member=declarations.get(canonical),owner=declarations.get(ownerId);
                if(member==null || owner==null) {population=false;continue;}
                require(member.startsWith("mem_") && owner.startsWith("cls_"),"invalid canonical method/owner kind");
                var targets=new TreeSet<String>();boolean mapped=overrideKnowledge.equals("KNOWN");
                for(String id:closure) {
                    String target=declarations.get(id);
                    if(target==null) {mapped=false;continue;}
                    require(target.startsWith("mem_") && !target.equals(member),"invalid canonical override target");
                    targets.add(target);
                }
                NativeEvidence.Evidence<String> overrides=mapped ? NativeEvidence.Evidence.known(List.copyOf(targets))
                    : NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,"native override relation is dependent or outside the retained declaration population");
                NativeEvidence.Evidence<NativeEvidence.TypeToken> returns=returnKnowledge.equals("KNOWN")
                    ? NativeEvidence.Evidence.known(List.of(new NativeEvidence.TypeToken(spelling.textValue(),NativeEvidence.TypeMeaning.COMPILER_TYPE)))
                    : NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,"compiler return type is dependent, invalid or undeduced");
                Method value=new Method(owner,overrides,returns),old=methods.putIfAbsent(member,value);
                require(old==null || old.equals(value),"conflicting native method redeclarations");
                String occurrence=text(row,"declarationAstId"),observed=declarations.get(occurrence);
                require(observed==null || observed.equals(member),"native method occurrence disagrees with retained declaration");
                aliases.put(occurrence,member);
            }
            return new Result(population,methods,aliases);
        } catch(IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ method semantics receipt: "+invalid.getMessage(),invalid);
        }
    }
    static List<NativeEvidence.Lookup> enrich(List<NativeEvidence.Lookup> lookups,Map<String,Method> methods) {
        return lookups.stream().map(row -> row.targets().knowledge()!=NativeEvidence.Knowledge.KNOWN ? row
            : new NativeEvidence.Lookup(row.contextId(),row.ownerClassifierId(),row.lookupName(),NativeEvidence.Evidence.known(
                row.targets().values().stream().map(target -> {
                    Method method=methods.get(target.declarationKey());
                    return method==null ? target : new NativeEvidence.LookupTarget(target.declarationKey(),target.mechanism(),
                        target.classifierPath(),target.access(),target.subobjectIdentity(),method.overrides());
                }).toList()))).toList();
    }
    private static Set<String> ids(JsonNode row,String key) {
        Set<String> result=new HashSet<>();
        for(JsonNode value:array(row,key))require(value.isTextual() && !value.textValue().isBlank() && result.add(value.textValue()),"invalid/duplicate override ID");
        return result;
    }
    private static JsonNode array(JsonNode row,String key) {JsonNode value=row.get(key);require(value!=null && value.isArray(),"missing array "+key);return value;}
    private static String text(JsonNode row,String key) {JsonNode value=row.get(key);require(value!=null && value.isTextual() && !value.textValue().isBlank(),"missing text "+key);return value.textValue();}
    private static String knowledge(JsonNode row,String key) {String value=text(row,key);require(value.equals("KNOWN") || value.equals("UNKNOWN"),"invalid knowledge "+key);return value;}
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
