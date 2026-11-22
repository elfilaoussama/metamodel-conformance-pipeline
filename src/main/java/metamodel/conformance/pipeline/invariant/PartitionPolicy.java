package metamodel.conformance.pipeline.invariant;

/** A projection strategy must be qualified against the profile's full-model witnesses. */
public enum PartitionPolicy {
    AUTO,
    ALL_CONNECTED,
    ALL_CLASSIFIER_UPWARD
}
