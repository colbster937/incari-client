package incari.platform.api.threads;

/** A worker failed a job, or stopped. */
public final class WorkerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkerException(String message) {
        super(message);
    }
}
