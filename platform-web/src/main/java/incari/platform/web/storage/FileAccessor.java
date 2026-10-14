package incari.platform.web.storage;

import java.io.IOException;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.teavm.runtime.fs.VirtualFileAccessor;

/**
 * An open file, as TeaVM's streams and {@code RandomAccessFile} use it: a position in the file's
 * bytes. Writes go straight into the tree and are noted in the {@link Journal}; the store hears of
 * them at the next flush, so {@link #flush} and {@link #close} have nothing to do.
 *
 * <p>Hot path for the game's file writes (region files): no allocation per call.
 */
final class FileAccessor implements VirtualFileAccessor {

    private static final byte[] NONE = new byte[0];

    private final @Nullable DataFile file;
    private final byte[] readOnlyBytes;
    private final int readOnlySize;
    private final @Nullable Journal journal;
    private final LongSupplier clock;
    private int position;
    private boolean closed;

    /** An accessor over a file of the tree; {@code journal} is null when it's opened for reading only. */
    FileAccessor(DataFile file, @Nullable Journal journal, LongSupplier clock, int position) {
        this.file = file;
        this.readOnlyBytes = NONE;
        this.readOnlySize = 0;
        this.journal = journal;
        this.clock = clock;
        this.position = position;
    }

    /** A read-only accessor over bytes that aren't in the tree (a mounted zip entry). */
    FileAccessor(FileNode.Bytes bytes, LongSupplier clock) {
        this.file = null;
        this.readOnlyBytes = bytes.data();
        this.readOnlySize = bytes.size();
        this.journal = null;
        this.clock = clock;
    }

    @Override
    public int read(byte[] buffer, int offset, int limit) throws IOException {
        ensureOpen();
        DataFile open = file;
        byte[] data = open != null ? open.data : readOnlyBytes;
        int size = open != null ? open.size : readOnlySize;
        int count = Math.max(0, Math.min(size - position, limit));
        if (count > 0) {
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
        }
        return count;
    }

    @Override
    public void write(byte[] buffer, int offset, int limit) throws IOException {
        DataFile target = writable();
        if (limit <= 0) {
            return;
        }
        long end = (long) position + limit;
        if (end > Integer.MAX_VALUE - 8) {
            throw new IOException("File too large for the page's file system: " + end + " bytes");
        }
        target.ensureCapacity((int) end);
        System.arraycopy(buffer, offset, target.data, position, limit);
        // Bytes between the old end and position are zero already (DataFile keeps them so).
        if (end > target.size) {
            target.size = (int) end;
        }
        target.markWritten(position, (int) end);
        position = (int) end;
        touch(target);
    }

    @Override
    public int tell() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public void seek(int target) throws IOException {
        ensureOpen();
        if (target < 0) {
            throw new IOException("Negative seek offset");
        }
        position = target;
    }

    @Override
    public void skip(int amount) throws IOException {
        ensureOpen();
        position = Math.max(0, position + amount);
    }

    @Override
    public int size() throws IOException {
        ensureOpen();
        DataFile open = file;
        return open != null ? open.size : readOnlySize;
    }

    @Override
    public void resize(int size) throws IOException {
        DataFile target = writable();
        if (size < 0) {
            throw new IOException("Negative file size");
        }
        if (size != target.size) {
            target.setSize(size);
            target.resized = true;
            touch(target);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
    }

    private DataFile writable() throws IOException {
        ensureOpen();
        DataFile target = file;
        if (target == null || journal == null) {
            throw new IOException("File not open for writing");
        }
        return target;
    }

    private void touch(DataFile target) {
        target.lastModified = clock.getAsLong();
        Journal changes = journal;
        if (changes != null) {
            changes.written(target);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("File closed");
        }
    }
}
