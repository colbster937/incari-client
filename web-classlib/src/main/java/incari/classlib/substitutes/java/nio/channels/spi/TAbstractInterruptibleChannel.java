package incari.classlib.substitutes.java.nio.channels.spi;

import incari.classlib.substitutes.java.nio.channels.TAsynchronousCloseException;
import incari.classlib.substitutes.java.nio.channels.TClosedByInterruptException;
import incari.classlib.substitutes.java.nio.channels.TInterruptibleChannel;
import java.io.IOException;
import java.nio.channels.Channel;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.nio.channels.spi.AbstractInterruptibleChannel}, which TeaVM lacks: the close-once
 * bookkeeping of an interruptible channel, and the {@link #begin()}/{@link #end(boolean)} bracket
 * around its I/O operations.
 *
 * <p>Nothing can interrupt a thread in the middle of an operation in the browser (threads are
 * coroutines, and the channels here don't suspend), so the bracket only honours an interrupt that
 * is already pending when an operation starts: like the JDK, the channel then closes and the
 * operation fails with {@code ClosedByInterruptException}.
 */
public abstract class TAbstractInterruptibleChannel implements Channel, TInterruptibleChannel {

    private final Object closeLock = new Object();
    private volatile boolean closed;
    // The thread whose pending interrupt closed this channel in begin(); end() reports it to it.
    private volatile @Nullable Thread interruptedBy;

    protected TAbstractInterruptibleChannel() {}

    @Override
    public final void close() throws IOException {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
            implCloseChannel();
        }
    }

    /** Closes the channel; called at most once, by {@link #close()}. */
    protected abstract void implCloseChannel() throws IOException;

    @Override
    public final boolean isOpen() {
        return !closed;
    }

    /** Marks the start of an I/O operation that could, on a real JVM, block indefinitely. */
    protected final void begin() {
        Thread current = Thread.currentThread();
        if (current.isInterrupted() && !closed) {
            interruptedBy = current;
            try {
                close();
            } catch (IOException e) {
                // The JDK closes an interrupted channel quietly as well; the caller learns of it
                // through the ClosedByInterruptException end() throws.
            }
        }
    }

    /**
     * Marks the end of an I/O operation.
     *
     * @param completed whether the operation completed, that is, had a visible effect
     */
    protected final void end(boolean completed) throws TAsynchronousCloseException {
        Thread current = Thread.currentThread();
        if (current.equals(interruptedBy)) {
            interruptedBy = null;
            throw new TClosedByInterruptException();
        }
        if (!completed && closed) {
            throw new TAsynchronousCloseException();
        }
    }
}
