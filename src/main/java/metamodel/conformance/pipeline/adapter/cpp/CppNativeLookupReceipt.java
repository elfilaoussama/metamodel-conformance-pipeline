package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.*;

/** Joins same-invocation compiler IDs; neither computes inheritance nor evaluates P0. */
final class CppNativeLookupReceipt {
    private CppNativeLookupReceipt() { }

    static List<NativeEvidence.Lookup> observe(String context, JsonNode receipt,
                                              Map<String,String> declarations) throws ObservationException {
        // Historical input-only helpers have no native lookup evidence.
        if (receipt == null) return List.of();
        try {
            if (!text(receipt,"format").equals("cpp-member-lookup-v1"))
                throw new IllegalArgumentException("unsupported native lookup format");
            var output=new ArrayList<NativeEvidence.Lookup>();
            var seen=new HashSet<String>();
            for (JsonNode cls:array(receipt,"classes")) {
                String owner=declarations.get(text(cls,"ownerAstId"));
                if (owner==null) continue; // Outside the independently retained source population.
                if (!owner.startsWith("cls_")) throw new IllegalArgumentException("lookup owner is not a classifier");
                if (!cls.path("dependent").isBoolean()) throw new IllegalArgumentException("missing dependent status");
                if (cls.get("dependent").asBoolean()) {
                    output.add(unknown(context,owner,"*",NativeEvidence.Knowledge.UNKNOWN,
                            "compiler-dependent class lookup"));
                    continue;
                }
                for (JsonNode lookup:array(cls,"lookups")) {
                    String name=text(lookup,"name");
                    if (!seen.add(owner+"\0"+name)) throw new IllegalArgumentException("duplicate class/name lookup");
                    var knowledge=NativeEvidence.Knowledge.valueOf(text(lookup,"knowledge"));
                    JsonNode targets=array(lookup,"targets");
                    if (knowledge!=NativeEvidence.Knowledge.KNOWN) {
                        output.add(unknown(context,owner,name,knowledge,"compiler lookup is "+knowledge));
                        continue;
                    }
                    var values=new LinkedHashSet<NativeEvidence.LookupTarget>();
                    String unavailable=null;
                    for (JsonNode target:targets) {
                        String member=declarations.get(text(target,"declarationAstId"));
                        if (member==null || !member.startsWith("mem_")) {
                            unavailable="native target is outside the retained declaration population"; break;
                        }
                        var access=NativeEvidence.Access.valueOf(text(target,"access"));
                        JsonNode paths=array(target,"paths");
                        if (paths.isEmpty()) { unavailable="native target lacks a subobject path"; break; }
                        for (JsonNode path:paths) {
                            var classes=new ArrayList<String>();
                            for (JsonNode id:array(path,"classes")) {
                                if (!id.isTextual()) throw new IllegalArgumentException("nontextual path classifier");
                                String canonical=declarations.get(id.asText());
                                if (canonical==null || !canonical.startsWith("cls_")) {
                                    unavailable="native path is outside the retained classifier population"; break;
                                }
                                classes.add(canonical);
                            }
                            if (unavailable!=null) break;
                            String targetClass=declarations.get(text(path,"targetClassAstId"));
                            if (classes.isEmpty() || !classes.get(0).equals(owner)
                                    || !classes.get(classes.size()-1).equals(targetClass))
                                throw new IllegalArgumentException("native path endpoints disagree");
                            JsonNode number=path.get("subobjectNumber"),virtual=path.get("virtual");
                            if (number==null || !number.isIntegralNumber() || !number.canConvertToInt()
                                    || number.asInt()<0 || virtual==null || !virtual.isBoolean())
                                throw new IllegalArgumentException("invalid native subobject identity");
                            String identity="cpp-subobject-v1:"+owner+":"+targetClass+":"
                                    +(virtual.asBoolean() ? "virtual" : "nonvirtual:"+number.asInt());
                            values.add(new NativeEvidence.LookupTarget(member,NativeEvidence.LookupMechanism.CPP_SUBOBJECT,
                                    classes,access,identity,NativeEvidence.Evidence.unavailable(
                                    NativeEvidence.Knowledge.UNKNOWN,"native override observation not yet available")));
                        }
                        if (unavailable!=null) break;
                    }
                    output.add(unavailable==null
                            ? new NativeEvidence.Lookup(context,owner,name,NativeEvidence.Evidence.known(List.copyOf(values)))
                            : unknown(context,owner,name,NativeEvidence.Knowledge.UNKNOWN,unavailable));
                }
            }
            output.sort(Comparator.comparing(NativeEvidence.Lookup::ownerClassifierId)
                    .thenComparing(NativeEvidence.Lookup::lookupName));
            return List.copyOf(output);
        } catch (IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ native lookup receipt: "+invalid.getMessage(),invalid);
        }
    }
    private static NativeEvidence.Lookup unknown(String context,String owner,String name,
                                                 NativeEvidence.Knowledge knowledge,String reason) {
        return new NativeEvidence.Lookup(context,owner,name,NativeEvidence.Evidence.unavailable(knowledge,reason));
    }
    private static JsonNode array(JsonNode value,String key) {
        JsonNode result=value.get(key);
        if (result==null || !result.isArray()) throw new IllegalArgumentException("missing array: "+key);
        return result;
    }
    private static String text(JsonNode value,String key) {
        JsonNode result=value.get(key);
        if (result==null || !result.isTextual() || result.asText().isBlank())
            throw new IllegalArgumentException("missing text: "+key);
        return result.asText();
    }
}
