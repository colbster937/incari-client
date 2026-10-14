package incari.classlib.substitutes.java.nio.channels;

import java.io.IOException;
import java.nio.channels.Channel;

/**
 * {@code java.nio.channels.FileLock}, which TeaVM lacks: a lock on a region of a file, held until
 * it is released or its channel closes.
 *
 * <p>Omitted: the constructor taking an {@code AsynchronousFileChannel}, which TeaVM lacks.
 */
public abstract class TFileLock implements AutoCloseable {

    private final TFileChannel channel;
    private final long position;
    private final long size;
    private final boolean shared;

    protected TFileLock(TFileChannel channel, long position, long size, boolean shared) {
        if (position < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Negative size");
        }
        if (position + size < 0) {
            throw new IllegalArgumentException("Negative position + size");
        }
        this.channel = channel;
        this.position = position;
        this.size = size;
        this.shared = shared;
    }

    public final TFileChannel channel() {
        return channel;
    }

    public Channel acquiredBy() {
        return channel;
    }

    public final long position() {
        return position;
    }

    public final long size() {
        return size;
    }

    public final boolean isShared() {
        return shared;
    }

    /** Whether this lock shares a byte with {@code [position, position + size)}; size 0 is unbounded. */
    public final boolean overlaps(long position, long size) {
        if (size < 0) {
            return false;
        }
        // A size of zero means "to the end of the file", as it does for FileChannel.lock. The
        // constructor keeps this.position + this.size from overflowing; the argument's end may,
        // so it saturates.
        long end = size == 0 || position + size < position ? Long.MAX_VALUE : position + size;
        return end > this.position && this.position + this.size > position;
    }

    public abstract boolean isValid();

    public abstract void release() throws IOException;

    @Override
    public final void close() throws IOException {
        release();
    }

    @Override
    public final String toString() {
        return getClass().getName() + "[" + position + ":" + size + " " + (shared ? "shared" : "exclusive") + " "
                + (isValid() ? "valid" : "invalid") + "]";
    }
}
