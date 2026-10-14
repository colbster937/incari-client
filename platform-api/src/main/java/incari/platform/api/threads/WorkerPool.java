package incari.platform.api.threads;

import java.nio.ByteBuffer;

/**
 * Workers running a program of their own beside the game, each with its own memory: the page's Web
 * Workers running the mesher's WebAssembly module (ADR 0023). They share nothing with the
 * game; everything they get and give back is bytes.
 *
 * <p>Call from the game's threads; in the browser, from the page's only thread.
 */
public interface WorkerPool {

    /** How many workers there are. */
    int size();

    /**
     * Sends {@code message} (its bytes from position to limit) to every worker, ahead of any job
     * submitted after it: the tables every later job reads, say. The bytes are copied before this
     * returns.
     */
    void broadcast(ByteBuffer message);

    /**
     * Sends a job to a worker (the one with the fewest in hand). The bytes, from position to limit,
     * are copied before this returns.
     */
    WorkerJob submit(ByteBuffer message);

    /** How many jobs have been submitted and not yet answered. */
    int inFlight();
}
