package metamodel.conformance.pipeline.model;

public record ImplementationBindingObservation(
        String technicalKey,
        String implementerClassifierId,
        String targetMemberKey,
        String bodyKey) {

    public ImplementationBindingObservation {
        technicalKey = CanonicalObservationValue.technicalId(
                technicalKey, "bind_", "technicalKey");
        implementerClassifierId = CanonicalObservationValue.technicalId(
                implementerClassifierId, "cls_", "implementerClassifierId");
        targetMemberKey = CanonicalObservationValue.technicalId(
                targetMemberKey, "mem_", "targetMemberKey");
        bodyKey = CanonicalObservationValue.technicalId(
                bodyKey, "body_", "bodyKey");
    }
}
