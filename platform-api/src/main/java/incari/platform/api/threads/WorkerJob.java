package incari.platform.api.threads;

import java.nio.ByteBuffer;

/** A job sent to a {@link WorkerPool}, and its answer. */
public interface WorkerJob {

    /**
     * Waits for the answer and returns its bytes, from position 0 to the limit, little-endian; valid
     * until {@link #release}. On a thread of the platform's background executor (its
     * {@link PlatformThreads#backgroundExecutor}), the pool goes on with its other tasks while this
     * one waits, and this one goes on in a later slot.
     *
     * @throws WorkerException if the worker failed or is gone
     */
    ByteBuffer await();

    /** Whether the answer is in: {@link #await} won't wait. */
    boolean done();

    /** Gives the answer's memory back. The job can't be used after this. */
    void release();
}
