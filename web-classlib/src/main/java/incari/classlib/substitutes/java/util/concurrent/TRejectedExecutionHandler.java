package incari.classlib.substitutes.java.util.concurrent;

/** {@code java.util.concurrent.RejectedExecutionHandler}. */
public interface TRejectedExecutionHandler {

    /** Handles a task {@code executor} can't accept. */
    void rejectedExecution(Runnable r, TThreadPoolExecutor executor);
}
