package metamodel.conformance.pipeline.adapter;

public final class ObservationException extends Exception {
    private static final long serialVersionUID = 1L;
    public ObservationException(String message) {
        super(message);
    }

    public ObservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
