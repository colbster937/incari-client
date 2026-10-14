package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ForkJoinWorkerThread}: a {@link TForkJoinPool} worker. Minecraft
 * subclasses it to name its threads and log their end ({@link #onStart}, {@link #onTermination}).
 *
 * <p>Workers share their pool's single queue (see {@link TForkJoinPool}), so there's no local
 * queue: {@link #getQueuedTaskCount} is always zero.
 */
public class TForkJoinWorkerThread extends Thread {

    private final TForkJoinPool pool;
    private final int poolIndex;

    /** Guarded by the pool's lock: whether the pool has stopped counting this worker. */
    boolean deregistered;

    // setName and setDaemon are final Thread methods that never call into a subclass.
    @SuppressWarnings("this-escape")
    protected TForkJoinWorkerThread(TForkJoinPool pool) {
        this.pool = pool;
        this.poolIndex = pool.nextWorkerIndex();
        setName(pool.workerName(poolIndex));
        // Pool threads never keep the program alive, as in the JDK.
        setDaemon(true);
    }

    public TForkJoinPool getPool() {
        return pool;
    }

    /** A number unique among this pool's workers so far. */
    public int getPoolIndex() {
        return poolIndex;
    }

    /** Always zero: the pool's workers share one queue. */
    public int getQueuedTaskCount() {
        return 0;
    }

    /** Runs on this thread before it takes any task. Does nothing by default. */
    protected void onStart() {}

    /** Runs on this thread as it ends, with the exception that ended it, if any. */
    protected void onTermination(@Nullable Throwable exception) {}

    /**
     * Runs the pool's tasks until the pool shuts down or this worker has idled past its keep-alive.
     * A task passed to {@code execute(Runnable)} that throws ends the worker (after
     * {@link #onTermination}) and reaches the uncaught exception handler, as in the JDK.
     */
    @Override
    public void run() {
        Throwable exception = null;
        try {
            onStart();
            pool.runWorker(this);
        } catch (Throwable t) {
            exception = t;
        } finally {
            try {
                onTermination(exception);
            } catch (Throwable t) {
                if (exception == null) {
                    exception = t;
                }
            } finally {
                pool.deregisterWorker(this, exception);
            }
        }
        if (exception != null) {
            throw Monitors.rethrow(exception);
        }
    }
}
