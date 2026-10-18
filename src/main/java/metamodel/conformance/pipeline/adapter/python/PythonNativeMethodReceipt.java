package metamodel.conformance.pipeline.adapter.python;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.*;

/** Validated source identities for guarded native MRO replacement and annotation facts. */
final class PythonNativeMethodReceipt {
    private PythonNativeMethodReceipt() { }
    record Method(String owner, MemberScope scope, NativeEvidence.Evidence<String> overrides,
                  NativeEvidence.Evidence<NativeEvidence.TypeToken> returnAnnotation) { }
    record Result(boolean populationComplete, Map<String,Method> methods) {
        Result { methods=Map.copyOf(methods); }
        static Result unavailable() { return new Result(false,Map.of()); }
    }
    static Result observe(JsonNode receipt,Map<String,String> classes,Map<String,String> declarations,
                          Map<String,String> owners) throws ObservationException {
        if(receipt==null || receipt.isNull())return Result.unavailable();
        try {
            require(text(receipt,"format").equals("python-source-method-semantics-v1"),"unsupported format");
            require(receipt.path("populationComplete").isBoolean(),"missing population completeness");
            boolean complete=receipt.get("populationComplete").booleanValue();
            var methods=new HashMap<String,Method>();var seen=new HashSet<String>();
            for(JsonNode row:array(receipt,"methods")) {
                String definition=text(row,"definitionKey");require(seen.add(definition),"duplicate native method definition");
                String member=declarations.get(definition),owner=classes.get(text(row,"ownerDefinitionKey"));
                String knowledge=knowledge(row,"overrideKnowledge"),returnKnowledge=knowledge(row,"returnKnowledge");
                var targets=new TreeSet<String>();boolean mapped=knowledge.equals("KNOWN");
                JsonNode targetRows=array(row,"overriddenDefinitionKeys");require(mapped || targetRows.isEmpty(),"unknown override has positive targets");
                for(JsonNode target:targetRows) {
                    require(target.isTextual() && !target.textValue().isBlank(),"invalid override definition");
                    String value=declarations.get(target.textValue());
                    if(value==null){mapped=false;continue;}
                    require(!value.equals(member) && targets.add(value),"self or duplicate override target");
                }
                JsonNode token=row.get("returnToken");require(token!=null,"missing return token");
                require(returnKnowledge.equals("KNOWN") ? token.isTextual() && !token.textValue().isBlank() : token.isNull(),"return knowledge/token mismatch");
                MemberScope scope=switch(text(row,"descriptorKind")) {
                    case "INSTANCE" -> MemberScope.INSTANCE;
                    case "STATIC" -> MemberScope.STATIC;
                    case "CLASS" -> MemberScope.UNKNOWN;
                    default -> throw new IllegalArgumentException("unsupported native descriptor kind");
                };
                if(member==null || owner==null){complete=false;continue;}
                require(member.startsWith("mem_") && owner.startsWith("cls_"),"invalid canonical method/owner kind");
                require(owner.equals(owners.get(member)),"native method owner disagrees with captured source");
                var overrides=mapped ? NativeEvidence.Evidence.known(List.copyOf(targets))
                    : NativeEvidence.Evidence.<String>unavailable(NativeEvidence.Knowledge.UNKNOWN,"native ancestor slot is unsupported or outside retained source declarations");
                var annotation=returnKnowledge.equals("KNOWN")
                    ? NativeEvidence.Evidence.known(List.of(new NativeEvidence.TypeToken(token.textValue(),NativeEvidence.TypeMeaning.SOURCE_ANNOTATION)))
                    : NativeEvidence.Evidence.<NativeEvidence.TypeToken>unavailable(NativeEvidence.Knowledge.UNKNOWN,"no supported bound builtin return annotation; runtime return values are not inferred");
                require(methods.putIfAbsent(member,new Method(owner,scope,overrides,annotation))==null,"duplicate canonical method");
            }
            return new Result(complete,methods);
        } catch(IllegalArgumentException invalid) {
            throw new ObservationException("invalid Python native method receipt: "+invalid.getMessage(),invalid);
        }
    }
    static List<NativeEvidence.Lookup> enrich(List<NativeEvidence.Lookup> rows,Map<String,Method> methods) {
        return rows.stream().map(row -> row.targets().knowledge()!=NativeEvidence.Knowledge.KNOWN ? row
            : new NativeEvidence.Lookup(row.contextId(),row.ownerClassifierId(),row.lookupName(),NativeEvidence.Evidence.known(
                row.targets().values().stream().map(target -> {
                    Method method=methods.get(target.declarationKey());
                    return method==null ? target : new NativeEvidence.LookupTarget(target.declarationKey(),target.mechanism(),
                        target.classifierPath(),target.access(),target.subobjectIdentity(),method.overrides());
                }).toList()))).toList();
    }
    private static JsonNode array(JsonNode row,String key) {JsonNode value=row.get(key);require(value!=null && value.isArray(),"missing array "+key);return value;}
    private static String text(JsonNode row,String key) {JsonNode value=row.get(key);require(value!=null && value.isTextual() && !value.textValue().isBlank(),"missing text "+key);return value.textValue();}
    private static String knowledge(JsonNode row,String key) {String value=text(row,key);require(value.equals("KNOWN") || value.equals("UNKNOWN"),"invalid knowledge "+key);return value;}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
}
