package incari.classlib.nio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The channel of a {@code FileInputStream} or {@code FileOutputStream} in the browser build.
 *
 * <p>TeaVM's file streams keep their file handle to themselves, and nothing outside them can
 * reach it, so this channel reads and writes through the stream. That keeps the JDK's rule that
 * the stream and its channel share one position, but the position can't be read or moved: the
 * operations that need it or the file's size ({@code position}, {@code size}, {@code truncate},
 * positional reads and writes, transfers) throw an {@link IOException}. To use those, open the
 * file with {@code FileChannel.open} or {@code RandomAccessFile}.
 */
final class StreamFileChannel extends BaseFileChannel {

    private final @Nullable InputStream in;
    private final @Nullable OutputStream out;
    private byte @Nullable [] scratch;

    private StreamFileChannel(@Nullable InputStream in, @Nullable OutputStream out) {
        super(in != null, out != null, new Object());
        this.in = in;
        this.out = out;
    }

    static StreamFileChannel reading(InputStream in) {
        return new StreamFileChannel(in, null);
    }

    static StreamFileChannel writing(OutputStream out) {
        return new StreamFileChannel(null, out);
    }

    @Override
    int implRead(ByteBuffer dst) throws IOException {
        InputStream stream = in();
        int length = dst.remaining();
        if (dst.hasArray()) {
            int n = stream.read(dst.array(), dst.arrayOffset() + dst.position(), length);
            if (n > 0) {
                dst.position(dst.position() + n);
            }
            return n;
        }
        byte[] buffer = scratch();
        int n = stream.read(buffer, 0, Math.min(length, buffer.length));
        if (n > 0) {
            dst.put(buffer, 0, n);
        }
        return n;
    }

    @Override
    void implWrite(ByteBuffer src) throws IOException {
        OutputStream stream = out();
        if (src.hasArray()) {
            stream.write(src.array(), src.arrayOffset() + src.position(), src.remaining());
            src.position(src.limit());
            return;
        }
        byte[] buffer = scratch();
        while (src.hasRemaining()) {
            int n = Math.min(src.remaining(), buffer.length);
            src.get(buffer, 0, n);
            stream.write(buffer, 0, n);
        }
    }

    @Override
    int implRead(ByteBuffer dst, long position) throws IOException {
        throw unseekable();
    }

    @Override
    void implWrite(ByteBuffer src, long position) throws IOException {
        throw unseekable();
    }

    @Override
    long implPosition() throws IOException {
        throw unseekable();
    }

    @Override
    void implPosition(long newPosition) throws IOException {
        throw unseekable();
    }

    @Override
    long implSize() throws IOException {
        throw unseekable();
    }

    @Override
    void implTruncate(long size) throws IOException {
        throw unseekable();
    }

    @Override
    void implForce() throws IOException {
        if (out != null) {
            out.flush();
        }
    }

    /** Closing a stream's channel closes the stream, as in the JDK. */
    @Override
    void implClose() throws IOException {
        if (in != null) {
            in.close();
        }
        if (out != null) {
            out.close();
        }
    }

    private InputStream in() {
        if (in == null) {
            throw new IllegalStateException("Not a reading channel");
        }
        return in;
    }

    private OutputStream out() {
        if (out == null) {
            throw new IllegalStateException("Not a writing channel");
        }
        return out;
    }

    private static IOException unseekable() {
        return new IOException("A file stream's channel can't seek or size its file in the browser;"
                + " open the file with FileChannel.open or RandomAccessFile instead");
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
