package incari.classlib.substitutes.java.util.zip;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.Deflater;

/**
 * {@code java.util.zip.DeflaterOutputStream}, replacing TeaVM's, which stopped feeding its
 * deflater on the deflater's own miscount of the input it had taken (see {@link TDeflater}). This
 * one feeds the deflater until it says it needs more, as the JDK's does, and flushes compressed
 * data on {@link #flush()} only when made with {@code syncFlush}.
 */
public class TDeflaterOutputStream extends FilterOutputStream {

    private static final int DEFAULT_BUFFER = 512;

    protected Deflater def;
    protected byte[] buf;

    private final boolean syncFlush;
    /** Made its own deflater, so it ends it on close. */
    private final boolean ownsDeflater;

    private boolean closed;

    /**
     * Finished: set by {@link #finish()}. TeaVM's {@code ZipOutputStream}, which this class sits
     * under in the browser build, reads and clears it between entries (package-private, as in
     * TeaVM's own {@code DeflaterOutputStream}).
     */
    boolean done;

    public TDeflaterOutputStream(OutputStream out, Deflater def, int size, boolean syncFlush) {
        this(out, def, size, syncFlush, false);
    }

    public TDeflaterOutputStream(OutputStream out, Deflater def, int size) {
        this(out, def, size, false, false);
    }

    public TDeflaterOutputStream(OutputStream out, Deflater def, boolean syncFlush) {
        this(out, def, DEFAULT_BUFFER, syncFlush, false);
    }

    public TDeflaterOutputStream(OutputStream out, Deflater def) {
        this(out, def, DEFAULT_BUFFER, false, false);
    }

    public TDeflaterOutputStream(OutputStream out, boolean syncFlush) {
        this(out, new Deflater(), DEFAULT_BUFFER, syncFlush, true);
    }

    public TDeflaterOutputStream(OutputStream out) {
        this(out, new Deflater(), DEFAULT_BUFFER, false, true);
    }

    private TDeflaterOutputStream(OutputStream out, Deflater def, int size, boolean syncFlush, boolean ownsDeflater) {
        super(Objects.requireNonNull(out, "out"));
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.def = Objects.requireNonNull(def, "def");
        this.buf = new byte[size];
        this.syncFlush = syncFlush;
        this.ownsDeflater = ownsDeflater;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (done || def.finished()) {
            throw new IOException("write beyond end of stream");
        }
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }
        def.setInput(b, off, len);
        while (!def.needsInput()) {
            deflate();
        }
    }

    /** Compresses the next part into the buffer and writes what came out. */
    protected void deflate() throws IOException {
        int len = def.deflate(buf, 0, buf.length);
        if (len > 0) {
            out.write(buf, 0, len);
        }
    }

    /** Writes the rest of the compressed data, leaving the stream underneath open. */
    public void finish() throws IOException {
        if (!def.finished()) {
            def.finish();
            while (!def.finished()) {
                deflate();
            }
        }
        done = true;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            finish();
        } finally {
            if (ownsDeflater) {
                def.end();
            }
            out.close();
        }
    }

    /**
     * With {@code syncFlush}, writes everything compressed so far, so the data written up to here
     * can be decompressed; either way, flushes the stream underneath.
     */
    @Override
    public void flush() throws IOException {
        if (syncFlush && !def.finished()) {
            int len;
            while ((len = def.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH)) > 0) {
                out.write(buf, 0, len);
                if (len < buf.length) {
                    break;
                }
            }
        }
        out.flush();
    }
}
