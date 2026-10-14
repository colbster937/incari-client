package incari.classlib.nio;

import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import incari.classlib.substitutes.java.nio.channels.TFileLock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * The {@code FileChannel} contract (argument checks, access modes, closed state, scattering and
 * gathering, transfers, locks) over a handful of primitive operations a subclass supplies.
 *
 * <p>Every primitive runs holding one lock, so each operation is atomic with respect to the
 * others and to the position, as the JDK requires. On the page, operations never suspend while
 * holding it: the virtual file system answers synchronously.
 */
abstract class BaseFileChannel extends TFileChannel {

    /** The chunk size for transfers and for copying through buffers without a backing array. */
    static final int CHUNK = 8192;

    private final boolean readable;
    private final boolean writable;
    // What LockTable groups locks by: the file's path, or the channel itself when it has none.
    private final Object lockKey;
    private final Object ioLock = new Object();

    BaseFileChannel(boolean readable, boolean writable, Object lockKey) {
        this.readable = readable;
        this.writable = writable;
        this.lockKey = lockKey;
    }

    // --- Primitives, each called holding the I/O lock on an open channel ----------------------------

    /** Reads at the position into {@code dst}, which has room; -1 at the end of the file. */
    abstract int implRead(ByteBuffer dst) throws IOException;

    /** Writes all of {@code src} at the position (at the end, in append mode). */
    abstract void implWrite(ByteBuffer src) throws IOException;

    /** Reads at {@code position} into {@code dst}, which has room; -1 at or past the end. */
    abstract int implRead(ByteBuffer dst, long position) throws IOException;

    /** Writes all of {@code src} at {@code position}, leaving the channel's position alone. */
    abstract void implWrite(ByteBuffer src, long position) throws IOException;

    abstract long implPosition() throws IOException;

    abstract void implPosition(long newPosition) throws IOException;

    abstract long implSize() throws IOException;

    /** Shrinks the file to {@code size}, which is below its current size. */
    abstract void implTruncate(long size) throws IOException;

    abstract void implForce() throws IOException;

    /** Releases what the channel holds; called once. */
    abstract void implClose() throws IOException;

    // --- The contract ------------------------------------------------------------------------------

    @Override
    public int read(ByteBuffer dst) throws IOException {
        checkReadInto(dst);
        if (!dst.hasRemaining()) {
            return 0;
        }
        return (int) io(() -> implRead(dst));
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, dsts.length);
        ensureOpen();
        for (int i = offset; i < offset + length; i++) {
            checkReadInto(dsts[i]);
        }
        return io(() -> {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                ByteBuffer dst = dsts[i];
                if (!dst.hasRemaining()) {
                    continue;
                }
                int n = implRead(dst);
                if (n < 0) {
                    return total == 0 ? -1 : total;
                }
                total += n;
                if (dst.hasRemaining()) {
                    break; // A short read: the file has nothing more for the next buffer yet.
                }
            }
            return total;
        });
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        checkWriteFrom(src);
        return (int) io(() -> {
            int n = src.remaining();
            if (n > 0) {
                implWrite(src);
            }
            return n;
        });
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, srcs.length);
        ensureOpen();
        for (int i = offset; i < offset + length; i++) {
            checkWriteFrom(srcs[i]);
        }
        return io(() -> {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                ByteBuffer src = srcs[i];
                int n = src.remaining();
                if (n > 0) {
                    implWrite(src);
                    total += n;
                }
            }
            return total;
        });
    }

    @Override
    public long position() throws IOException {
        ensureOpen();
        return io(this::implPosition);
    }

    @Override
    public TFileChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        io(() -> {
            implPosition(newPosition);
            return 0;
        });
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return io(this::implSize);
    }

    @Override
    public TFileChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0) {
            throw new IllegalArgumentException("Negative size");
        }
        if (!writable) {
            throw new NonWritableChannelException();
        }
        io(() -> {
            if (size < implSize()) {
                implTruncate(size);
            }
            if (implPosition() > size) {
                implPosition(size);
            }
            return 0;
        });
        return this;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        ensureOpen();
        io(() -> {
            implForce();
            return 0;
        });
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        ensureOpen();
        if (!target.isOpen()) {
            throw new ClosedChannelException();
        }
        if (!readable) {
            throw new NonReadableChannelException();
        }
        checkRange(position, count);
        // Chunk by chunk, without holding the I/O lock across the target's writes: the target
        // may be another channel with its own lock, or this one.
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(CHUNK, Math.max(count, 1)));
        long total = 0;
        while (total < count) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), count - total));
            int n = read(buffer, position + total);
            if (n <= 0) {
                break;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                if (target.write(buffer) <= 0) {
                    break; // A target that takes nothing now (non-blocking) ends the transfer.
                }
            }
            total += n - buffer.remaining();
            if (buffer.hasRemaining()) {
                break;
            }
        }
        return total;
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        ensureOpen();
        if (!src.isOpen()) {
            throw new ClosedChannelException();
        }
        if (!writable) {
            throw new NonWritableChannelException();
        }
        checkRange(position, count);
        if (position > size()) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(CHUNK, Math.max(count, 1)));
        long total = 0;
        while (total < count) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), count - total));
            int n = src.read(buffer);
            if (n <= 0) {
                break;
            }
            buffer.flip();
            write(buffer, position + total);
            total += n;
        }
        return total;
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        checkReadInto(dst);
        if (!dst.hasRemaining()) {
            return 0;
        }
        return (int) io(() -> implRead(dst, position));
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        checkWriteFrom(src);
        return (int) io(() -> {
            int n = src.remaining();
            if (n > 0) {
                implWrite(src, position);
            }
            return n;
        });
    }

    /** A page has no way to map a file into memory. */
    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        throw new UnsupportedOperationException("Memory-mapped files are not available in the browser");
    }

    @Override
    public TFileLock lock(long position, long size, boolean shared) throws IOException {
        return tryLock(position, size, shared);
    }

    /**
     * Grants the lock at once: one page is one process, so the only locks that could conflict are
     * this program's own, and the contract makes those an {@code OverlappingFileLockException}
     * rather than a wait.
     */
    @Override
    public TFileLock tryLock(long position, long size, boolean shared) throws IOException {
        ensureOpen();
        checkRange(position, size);
        if (position + size < 0) {
            throw new IllegalArgumentException("Negative position + size");
        }
        if (shared && !readable) {
            throw new NonReadableChannelException();
        }
        if (!shared && !writable) {
            throw new NonWritableChannelException();
        }
        // A size of zero locks from the position to the end of the file, however it grows.
        long lockSize = size == 0 ? Long.MAX_VALUE - position : size;
        return LockTable.acquire(this, lockKey, position, lockSize, shared);
    }

    @Override
    protected final void implCloseChannel() throws IOException {
        LockTable.releaseAll(lockKey, this);
        synchronized (ioLock) {
            implClose();
        }
    }

    // --- Helpers -----------------------------------------------------------------------------------

    /** An I/O primitive, run by {@link #io}. */
    @FunctionalInterface
    interface Primitive {
        long run() throws IOException;
    }

    /** Runs {@code primitive} holding the I/O lock, inside the interruptible-channel bracket. */
    private long io(Primitive primitive) throws IOException {
        boolean completed = false;
        begin();
        try {
            synchronized (ioLock) {
                // Checked again under the lock: another thread may have closed the channel.
                ensureOpen();
                long result = primitive.run();
                completed = true;
                return result;
            }
        } finally {
            end(completed);
        }
    }

    final void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    private void checkReadInto(ByteBuffer dst) throws ClosedChannelException {
        Objects.requireNonNull(dst);
        ensureOpen();
        if (!readable) {
            throw new NonReadableChannelException();
        }
        if (dst.isReadOnly()) {
            throw new IllegalArgumentException("Read-only buffer");
        }
    }

    private void checkWriteFrom(ByteBuffer src) throws ClosedChannelException {
        Objects.requireNonNull(src);
        ensureOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
    }

    private static void checkRange(long position, long count) {
        if (position < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Negative size");
        }
    }
}
