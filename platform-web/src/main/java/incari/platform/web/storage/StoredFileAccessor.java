package incari.platform.web.storage;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * An open in-place file in a worker that opens them where they are (ADR 0041): every read and write
 * goes straight to its handle, none to the tree's memory or its journal (the store is the handle's
 * file itself). Closing it leaves the handle open for the next: the server opens a region file's
 * channel and closes it as its cache of open regions turns over.
 */
final class StoredFileAccessor implements org.teavm.runtime.fs.VirtualFileAccessor {

    private final StoredFile file;
    private final InPlaceStore.Handle handle;
    private final boolean writable;
    private final LongSupplier clock;
    private int position;
    private boolean closed;

    StoredFileAccessor(
            StoredFile file, InPlaceStore.Handle handle, boolean writable, LongSupplier clock, int position) {
        this.file = file;
        this.handle = handle;
        this.writable = writable;
        this.clock = clock;
        this.position = position;
    }

    @Override
    public int read(byte[] buffer, int offset, int limit) throws IOException {
        ensureOpen();
        int count = Math.max(0, Math.min(file.size - position, limit));
        if (count == 0) {
            return 0;
        }
        int read = handle.read(buffer, offset, count, position);
        position += read;
        return read;
    }

    @Override
    public void write(byte[] buffer, int offset, int limit) throws IOException {
        ensureWritable();
        if (limit <= 0) {
            return;
        }
        long end = (long) position + limit;
        if (end > Integer.MAX_VALUE - 8) {
            throw new IOException("File too large for the page's file system: " + end + " bytes");
        }
        handle.write(buffer, offset, limit, position);
        position = (int) end;
        file.size = Math.max(file.size, position);
        file.lastModified = clock.getAsLong();
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
        return file.size;
    }

    @Override
    public void resize(int size) throws IOException {
        ensureWritable();
        if (size < 0) {
            throw new IOException("Negative file size");
        }
        if (size != file.size) {
            handle.truncate(size);
            file.size = size;
            file.lastModified = clock.getAsLong();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (writable) {
            handle.flush();
        }
    }

    private void ensureWritable() throws IOException {
        ensureOpen();
        if (!writable) {
            throw new IOException("File not open for writing");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("File closed");
        }
    }
}
