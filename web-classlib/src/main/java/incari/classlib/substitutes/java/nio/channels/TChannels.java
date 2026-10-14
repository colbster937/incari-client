package incari.classlib.substitutes.java.nio.channels;

import incari.classlib.substitutes.java.nio.channels.spi.TAbstractInterruptibleChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.Objects;

/**
 * {@code java.nio.channels.Channels}, which TeaVM lacks: streams, readers and writers over
 * channels, and channels over streams.
 *
 * <p>The streams expect blocking channels, which every channel in the browser build is: a
 * channel that moves no bytes makes them fail rather than spin, since spinning would hang the
 * page. The buffer-size hints of {@code newReader} and {@code newWriter} are ignored.
 *
 * <p>Omitted: the {@code AsynchronousByteChannel} overloads, since TeaVM lacks that type.
 */
public final class TChannels {

    private static final int CHUNK = 8192;

    private TChannels() {}

    public static InputStream newInputStream(ReadableByteChannel ch) {
        return new ChannelInputStream(Objects.requireNonNull(ch, "ch"));
    }

    public static OutputStream newOutputStream(WritableByteChannel ch) {
        return new ChannelOutputStream(Objects.requireNonNull(ch, "ch"));
    }

    public static ReadableByteChannel newChannel(InputStream in) {
        return new InputStreamChannel(Objects.requireNonNull(in, "in"));
    }

    public static WritableByteChannel newChannel(OutputStream out) {
        return new OutputStreamChannel(Objects.requireNonNull(out, "out"));
    }

    public static Reader newReader(ReadableByteChannel ch, CharsetDecoder dec, int minBufferCap) {
        Objects.requireNonNull(dec, "dec");
        return new InputStreamReader(newInputStream(ch), dec.reset());
    }

    public static Reader newReader(ReadableByteChannel ch, String csName) {
        Objects.requireNonNull(csName, "csName");
        return newReader(ch, Charset.forName(csName).newDecoder(), -1);
    }

    public static Reader newReader(ReadableByteChannel ch, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return newReader(ch, charset.newDecoder(), -1);
    }

    public static Writer newWriter(WritableByteChannel ch, CharsetEncoder enc, int minBufferCap) {
        Objects.requireNonNull(enc, "enc");
        return new OutputStreamWriter(newOutputStream(ch), enc.reset());
    }

    public static Writer newWriter(WritableByteChannel ch, String csName) {
        Objects.requireNonNull(csName, "csName");
        return newWriter(ch, Charset.forName(csName).newEncoder(), -1);
    }

    public static Writer newWriter(WritableByteChannel ch, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return newWriter(ch, charset.newEncoder(), -1);
    }

    private static IOException stalled() {
        return new IOException("The channel moved no bytes; only blocking channels can back a stream");
    }

    /** Reads from a channel. */
    private static final class ChannelInputStream extends InputStream {

        private final ReadableByteChannel ch;
        private final byte[] one = new byte[1];

        ChannelInputStream(ReadableByteChannel ch) {
            this.ch = ch;
        }

        @Override
        public synchronized int read() throws IOException {
            int n = read(one, 0, 1);
            if (n == 0) {
                throw stalled();
            }
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            return ch.read(ByteBuffer.wrap(b, off, len));
        }

        @Override
        public synchronized int available() throws IOException {
            if (ch instanceof SeekableByteChannel seekable) {
                long left = seekable.size() - seekable.position();
                return (int) Math.max(0, Math.min(Integer.MAX_VALUE, left));
            }
            return 0;
        }

        @Override
        public synchronized long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            if (ch instanceof SeekableByteChannel seekable) {
                // Seeks instead of reading, and stops at the end of the file.
                long position = seekable.position();
                long size = seekable.size();
                if (position >= size) {
                    return 0;
                }
                long target = n > size - position ? size : position + n;
                seekable.position(target);
                return target - position;
            }
            return super.skip(n);
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    /** Writes to a channel. */
    private static final class ChannelOutputStream extends OutputStream {

        private final WritableByteChannel ch;
        private final byte[] one = new byte[1];

        ChannelOutputStream(WritableByteChannel ch) {
            this.ch = ch;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            one[0] = (byte) b;
            write(one, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
            while (buffer.hasRemaining()) {
                if (ch.write(buffer) <= 0) {
                    throw stalled();
                }
            }
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    /** A channel reading from a stream. */
    private static final class InputStreamChannel extends TAbstractInterruptibleChannel implements ReadableByteChannel {

        private final InputStream in;
        private final Object readLock = new Object();
        private byte[] buffer = new byte[0];

        InputStreamChannel(InputStream in) {
            this.in = in;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            int length = dst.remaining();
            if (length == 0) {
                return 0;
            }
            synchronized (readLock) {
                boolean completed = false;
                begin();
                try {
                    int total = 0;
                    boolean end = false;
                    // Reads what the stream has without blocking once some bytes are in, like a
                    // channel read: at least one byte, then whatever is available.
                    while (total < length) {
                        if (total > 0 && in.available() <= 0) {
                            break;
                        }
                        int chunk = Math.min(length - total, CHUNK);
                        if (buffer.length < chunk) {
                            buffer = new byte[chunk];
                        }
                        int n = in.read(buffer, 0, chunk);
                        if (n < 0) {
                            end = true;
                            break;
                        }
                        dst.put(buffer, 0, n);
                        total += n;
                        if (n == 0) {
                            break;
                        }
                    }
                    completed = true;
                    return end && total == 0 ? -1 : total;
                } finally {
                    end(completed);
                }
            }
        }

        @Override
        protected void implCloseChannel() throws IOException {
            in.close();
        }
    }

    /** A channel writing to a stream. */
    private static final class OutputStreamChannel extends TAbstractInterruptibleChannel
            implements WritableByteChannel {

        private final OutputStream out;
        private final Object writeLock = new Object();
        private byte[] buffer = new byte[0];

        OutputStreamChannel(OutputStream out) {
            this.out = out;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            int length = src.remaining();
            synchronized (writeLock) {
                boolean completed = false;
                begin();
                try {
                    while (src.hasRemaining()) {
                        int chunk = Math.min(src.remaining(), CHUNK);
                        if (buffer.length < chunk) {
                            buffer = new byte[chunk];
                        }
                        src.get(buffer, 0, chunk);
                        out.write(buffer, 0, chunk);
                    }
                    completed = true;
                    return length;
                } finally {
                    end(completed);
                }
            }
        }

        @Override
        protected void implCloseChannel() throws IOException {
            out.close();
        }
    }
}
