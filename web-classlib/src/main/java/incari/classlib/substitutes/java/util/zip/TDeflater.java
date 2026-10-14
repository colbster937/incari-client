package incari.classlib.substitutes.java.util.zip;

import com.jcraft.jzlib.JZlib;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * {@code java.util.zip.Deflater} over jzlib, replacing TeaVM's. TeaVM's threw on zlib's "no
 * progress possible" answer (-5), which a deflate stream gets in the ordinary course (a call with
 * all input already taken in), and kept its own count of the input taken instead of asking zlib:
 * its {@code DeflaterOutputStream} failed partway through writing a singleplayer chunk, which left
 * the chunk unreadable. Here zlib's own state answers {@link #needsInput()}, and no progress is just
 * no output.
 *
 * <p>As the JDK's, the input array is held, not copied, until it's taken in, and a new level or
 * strategy applies from the next {@code deflate}.
 */
public class TDeflater implements AutoCloseable {

    public static final int DEFLATED = 8;
    public static final int NO_COMPRESSION = 0;
    public static final int BEST_SPEED = 1;
    public static final int BEST_COMPRESSION = 9;
    public static final int DEFAULT_COMPRESSION = -1;
    public static final int FILTERED = 1;
    public static final int HUFFMAN_ONLY = 2;
    public static final int DEFAULT_STRATEGY = 0;
    public static final int NO_FLUSH = 0;
    public static final int SYNC_FLUSH = 2;
    public static final int FULL_FLUSH = 3;

    private static final int WINDOW_BITS = 15;
    private static final byte[] NO_INPUT = new byte[0];

    private final boolean nowrap;
    private com.jcraft.jzlib.@org.jspecify.annotations.Nullable Deflater zlib;
    private int level;
    private int strategy = DEFAULT_STRATEGY;
    private boolean paramsChanged;
    private boolean finish;
    private boolean finished;
    private boolean ended;

    public TDeflater(int level, boolean nowrap) {
        checkLevel(level);
        this.level = level;
        this.nowrap = nowrap;
        this.zlib = start();
    }

    public TDeflater(int level) {
        this(level, false);
    }

    public TDeflater() {
        this(DEFAULT_COMPRESSION, false);
    }

    private com.jcraft.jzlib.Deflater start() {
        com.jcraft.jzlib.Deflater stream = new com.jcraft.jzlib.Deflater();
        int result = stream.init(level, WINDOW_BITS, nowrap);
        if (result != JZlib.Z_OK) {
            throw new InternalError("zlib couldn't start a deflate stream: " + result);
        }
        if (strategy != DEFAULT_STRATEGY) {
            stream.params(level, strategy);
        }
        stream.setInput(NO_INPUT, 0, 0, false);
        return stream;
    }

    private com.jcraft.jzlib.Deflater stream() {
        com.jcraft.jzlib.Deflater stream = zlib;
        if (stream == null) {
            throw new NullPointerException("Deflater has been closed");
        }
        return stream;
    }

    public void setInput(byte[] input, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, input.length);
        stream().setInput(input, offset, length, false);
    }

    public void setInput(byte[] input) {
        setInput(input, 0, input.length);
    }

    /** Copies what remains of the buffer and takes all of it as input; the buffer is used up. */
    public void setInput(ByteBuffer input) {
        byte[] bytes = new byte[input.remaining()];
        input.get(bytes);
        setInput(bytes, 0, bytes.length);
    }

    public void setDictionary(byte[] dictionary, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dictionary.length);
        byte[] bytes = offset == 0 ? dictionary : java.util.Arrays.copyOfRange(dictionary, offset, offset + length);
        int result = stream().setDictionary(bytes, length);
        if (result != JZlib.Z_OK) {
            throw new IllegalArgumentException("zlib refused the dictionary: " + result);
        }
    }

    public void setDictionary(byte[] dictionary) {
        setDictionary(dictionary, 0, dictionary.length);
    }

    public void setStrategy(int strategy) {
        if (strategy != DEFAULT_STRATEGY && strategy != FILTERED && strategy != HUFFMAN_ONLY) {
            throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
        if (this.strategy != strategy) {
            this.strategy = strategy;
            paramsChanged = true;
        }
    }

    public void setLevel(int level) {
        checkLevel(level);
        if (this.level != level) {
            this.level = level;
            paramsChanged = true;
        }
    }

    /** True when the input set last has all been taken in, and more is needed to go on. */
    public boolean needsInput() {
        return stream().avail_in <= 0;
    }

    public void finish() {
        finish = true;
    }

    public boolean finished() {
        return finished;
    }

    public int deflate(byte[] output, int offset, int length) {
        return deflate(output, offset, length, NO_FLUSH);
    }

    public int deflate(byte[] output) {
        return deflate(output, 0, output.length, NO_FLUSH);
    }

    /** Compresses into the buffer's remaining space, advancing its position. */
    public int deflate(ByteBuffer output) {
        return deflate(output, NO_FLUSH);
    }

    public int deflate(ByteBuffer output, int flush) {
        byte[] bytes = new byte[output.remaining()];
        int written = deflate(bytes, 0, bytes.length, flush);
        output.put(bytes, 0, written);
        return written;
    }

    /**
     * Compresses as much as fits: {@link #NO_FLUSH} lets zlib hold data back to compress better,
     * {@link #SYNC_FLUSH} and {@link #FULL_FLUSH} make it hand out everything taken in so far.
     * After {@link #finish()}, every call finishes the stream.
     *
     * @return the bytes written, 0 when no progress was possible (zlib wants input or room)
     */
    public int deflate(byte[] output, int offset, int length, int flush) {
        Objects.checkFromIndexSize(offset, length, output.length);
        if (flush != NO_FLUSH && flush != SYNC_FLUSH && flush != FULL_FLUSH) {
            throw new IllegalArgumentException("Unknown flush mode: " + flush);
        }
        com.jcraft.jzlib.Deflater stream = stream();
        if (finished || length == 0) {
            return 0;
        }
        long before = stream.total_out;
        stream.setOutput(output, offset, length);
        if (paramsChanged) {
            // Applies from here on; zlib may first compress what it holds with the old ones.
            int result = stream.params(level, strategy);
            if (result != JZlib.Z_OK && result != JZlib.Z_BUF_ERROR) {
                throw new InternalError("zlib refused the new level or strategy: " + result);
            }
            paramsChanged = result == JZlib.Z_BUF_ERROR;
        }
        int mode = finish ? JZlib.Z_FINISH : flush;
        int result = stream.deflate(mode);
        switch (result) {
            case JZlib.Z_STREAM_END -> finished = true;
            case JZlib.Z_OK, JZlib.Z_BUF_ERROR -> {
                // Progress, or none possible this call: both are the caller's to go on from.
            }
            default -> throw new InternalError("zlib failed to deflate: " + result);
        }
        return (int) (stream.total_out - before);
    }

    public int getAdler() {
        return (int) stream().getAdler();
    }

    /** The input taken in so far, modulo 2^32: {@link #getBytesRead()} is the whole count. */
    public int getTotalIn() {
        return (int) getBytesRead();
    }

    public long getBytesRead() {
        return stream().total_in;
    }

    /** The output written so far, modulo 2^32: {@link #getBytesWritten()} is the whole count. */
    public int getTotalOut() {
        return (int) getBytesWritten();
    }

    public long getBytesWritten() {
        return stream().total_out;
    }

    /** Starts over with a new stream, keeping the level and strategy; jzlib has no reset of its own. */
    public void reset() {
        stream().end();
        zlib = start();
        finish = false;
        finished = false;
        paramsChanged = false;
    }

    public void end() {
        if (!ended) {
            ended = true;
            com.jcraft.jzlib.Deflater stream = zlib;
            zlib = null;
            if (stream != null) {
                stream.end();
            }
        }
    }

    @Override
    public void close() {
        end();
    }

    private static void checkLevel(int level) {
        if ((level < 0 || level > 9) && level != DEFAULT_COMPRESSION) {
            throw new IllegalArgumentException("Invalid compression level: " + level);
        }
    }
}
