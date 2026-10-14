package incari.classlib.substitutes.java.util.zip;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * {@code java.util.zip.GZIPOutputStream}, replacing TeaVM's, whose {@code flush()} always ran a
 * sync-flush deflate and failed on zlib's "no progress" answer: every compressed NBT file the game
 * wrote failed on its close (singleplayer's {@code level.dat} first). As the JDK's, this one's
 * {@code flush()} compresses what it has only when made with {@code syncFlush}
 * ({@link TDeflaterOutputStream}).
 */
public class TGZIPOutputStream extends DeflaterOutputStream {

    private static final int MAGIC = 0x8B1F;
    private static final int DEFAULT_BUFFER = 512;

    /** The uncompressed bytes so far, for the trailer. */
    protected CRC32 crc = new CRC32();

    private boolean trailerWritten;

    public TGZIPOutputStream(OutputStream out) throws IOException {
        this(out, DEFAULT_BUFFER, false);
    }

    public TGZIPOutputStream(OutputStream out, int size) throws IOException {
        this(out, size, false);
    }

    public TGZIPOutputStream(OutputStream out, boolean syncFlush) throws IOException {
        this(out, DEFAULT_BUFFER, syncFlush);
    }

    public TGZIPOutputStream(OutputStream out, int size, boolean syncFlush) throws IOException {
        // Raw deflate (no zlib wrapper): gzip has its own header and trailer.
        super(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true), size, syncFlush);
        writeHeader();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        super.write(bytes, offset, length);
        crc.update(bytes, offset, length);
    }

    @Override
    public void finish() throws IOException {
        super.finish();
        if (!trailerWritten) {
            trailerWritten = true;
            writeInt((int) crc.getValue());
            // The input size modulo 2^32, as gzip stores it.
            writeInt((int) def.getBytesRead());
        }
    }

    /** Finishes the stream, closes the one underneath, and ends the deflater it made. */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            def.end();
        }
    }

    private void writeHeader() throws IOException {
        // Magic, the deflate method, no flags, no modification time, no extra flags and an unknown
        // operating system (255): the same ten bytes the JDK writes.
        byte[] header = {(byte) MAGIC, (byte) (MAGIC >> 8), Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, (byte) 0xFF};
        out.write(header);
    }

    private void writeInt(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
