package incari.classlib.nio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * A file channel over a {@link RandomAccessFile}, which TeaVM keeps in its virtual file system.
 * The channel's position is the file's pointer, so a channel from
 * {@code RandomAccessFile.getChannel()} shares it with the file as the JDK requires.
 */
final class RandomAccessFileChannel extends BaseFileChannel {

    private final RandomAccessFile file;
    private final boolean append;
    private final @Nullable File deleteOnClose;
    // Copies through buffers with no backing array (direct ones); guarded by the I/O lock.
    private byte @Nullable [] scratch;

    RandomAccessFileChannel(
            RandomAccessFile file,
            boolean readable,
            boolean writable,
            boolean append,
            @Nullable File deleteOnClose,
            Object lockKey) {
        super(readable, writable, lockKey);
        this.file = file;
        this.append = append;
        this.deleteOnClose = deleteOnClose;
    }

    @Override
    int implRead(ByteBuffer dst) throws IOException {
        // Never asks the file for zero bytes: TeaVM's RandomAccessFile reports that as the end.
        int length = dst.remaining();
        if (dst.hasArray()) {
            int n = file.read(dst.array(), dst.arrayOffset() + dst.position(), length);
            if (n > 0) {
                dst.position(dst.position() + n);
            }
            return n;
        }
        byte[] buffer = scratch();
        int n = file.read(buffer, 0, Math.min(length, buffer.length));
        if (n > 0) {
            dst.put(buffer, 0, n);
        }
        return n;
    }

    @Override
    void implWrite(ByteBuffer src) throws IOException {
        if (append) {
            file.seek(file.length());
        }
        writeAll(src);
    }

    @Override
    int implRead(ByteBuffer dst, long position) throws IOException {
        if (position >= file.length()) {
            return -1;
        }
        long saved = file.getFilePointer();
        try {
            file.seek(position);
            return implRead(dst);
        } finally {
            file.seek(saved);
        }
    }

    @Override
    void implWrite(ByteBuffer src, long position) throws IOException {
        long saved = file.getFilePointer();
        try {
            file.seek(position);
            writeAll(src);
        } finally {
            file.seek(saved);
        }
    }

    @Override
    long implPosition() throws IOException {
        // In append mode every write lands at the end, and the JDK reports the position there.
        return append ? file.length() : file.getFilePointer();
    }

    @Override
    void implPosition(long newPosition) throws IOException {
        file.seek(newPosition);
    }

    @Override
    long implSize() throws IOException {
        return file.length();
    }

    @Override
    void implTruncate(long size) throws IOException {
        file.setLength(size);
    }

    /**
     * Nothing to do: the page's virtual file system has no write cache between the channel and
     * the storage behind it.
     */
    @Override
    void implForce() {}

    @Override
    void implClose() throws IOException {
        try {
            file.close();
        } finally {
            if (deleteOnClose != null) {
                // Best effort, as in the JDK: a file that can't be deleted stays.
                deleteOnClose.delete();
            }
        }
    }

    private void writeAll(ByteBuffer src) throws IOException {
        if (src.hasArray()) {
            file.write(src.array(), src.arrayOffset() + src.position(), src.remaining());
            src.position(src.limit());
            return;
        }
        byte[] buffer = scratch();
        while (src.hasRemaining()) {
            int n = Math.min(src.remaining(), buffer.length);
            src.get(buffer, 0, n);
            file.write(buffer, 0, n);
        }
    }

    private byte[] scratch() {
        byte[] buffer = scratch;
        if (buffer == null) {
            buffer = new byte[CHUNK];
            scratch = buffer;
        }
        return buffer;
    }
}
