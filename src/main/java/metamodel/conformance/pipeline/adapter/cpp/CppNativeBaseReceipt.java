package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.JsonNode;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.*;

/** Same-pass compiler base identities; name hints never resolve unavailable targets. */
final class CppNativeBaseReceipt {
    private CppNativeBaseReceipt() { }

    static Map<String,List<NativeEvidence.DirectBase>> observe(JsonNode receipt,
            Map<String,String> declarations) throws ObservationException {
        if (receipt == null) return Map.of();
        try {
            if (!receipt.path("format").asText().equals("cpp-member-lookup-v1")
                    || !receipt.path("classes").isArray())
                throw new IllegalArgumentException("unsupported base receipt");
            var output=new LinkedHashMap<String,List<NativeEvidence.DirectBase>>();
            for (JsonNode cls:receipt.get("classes")) {
                String owner=declarations.get(cls.path("ownerAstId").asText());
                if (owner==null) continue;
                if (!owner.startsWith("cls_")) throw new IllegalArgumentException("base owner is not a classifier");
                if (!cls.has("bases")) continue; // Historical helper: absence is not known-empty.
                if (!cls.get("bases").isArray()) throw new IllegalArgumentException("base list is not an array");
                var bases=new ArrayList<NativeEvidence.DirectBase>();
                for (JsonNode base:cls.get("bases")) {
                    String spelling=base.path("sourceSpelling").asText();
                    if (spelling.isBlank() || !base.path("virtual").isBoolean())
                        throw new IllegalArgumentException("incomplete base record");
                    var knowledge=NativeEvidence.Knowledge.valueOf(base.path("knowledge").asText());
                    String target=declarations.get(base.path("targetClassAstId").asText());
                    if (target!=null && !target.startsWith("cls_"))
                        throw new IllegalArgumentException("base target is not a classifier");
                    var identity=knowledge==NativeEvidence.Knowledge.KNOWN && target!=null
                        ? NativeEvidence.Evidence.known(List.of(target))
                        : NativeEvidence.Evidence.<String>unavailable(
                            knowledge==NativeEvidence.Knowledge.KNOWN ? NativeEvidence.Knowledge.UNKNOWN : knowledge,
                            "compiler base identity unavailable in retained declaration population");
                    bases.add(new NativeEvidence.DirectBase(spelling,identity,
                        NativeEvidence.Access.valueOf(base.path("access").asText()),
                        base.get("virtual").asBoolean() ? NativeEvidence.Virtuality.VIRTUAL : NativeEvidence.Virtuality.NON_VIRTUAL));
                }
                if (output.putIfAbsent(owner,List.copyOf(bases))!=null)
                    throw new IllegalArgumentException("duplicate base owner");
            }
            return Map.copyOf(output);
        } catch (IllegalArgumentException invalid) {
            throw new ObservationException("invalid C++ native base receipt: "+invalid.getMessage(),invalid);
        }
    }
}
