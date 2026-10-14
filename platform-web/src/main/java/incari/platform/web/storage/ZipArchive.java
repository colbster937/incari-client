package incari.platform.web.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A zip file read in place: its central directory is parsed once, and an entry's bytes are read
 * and inflated only when someone opens it. Written from the zip format's specification (PKWARE's
 * APPNOTE); stored and deflated entries only, no ZIP64, no encryption, which is all a Java jar
 * of this size uses.
 */
final class ZipArchive {

    /** Where the zip's bytes are: a JavaScript buffer in the page, an array in tests. */
    interface Source {
        int size();

        /** {@code length} bytes from {@code position}. */
        byte[] read(int position, int length) throws IOException;

        /**
         * The entries at these local headers, each its header and {@code compressedSizes[i]} bytes
         * of data, copied one after another into a source of their own ({@link #compact}), with
         * where each now starts. In an array by default; the page's buffer copies them into a new
         * buffer of the page's, in one pass.
         */
        default Kept keep(int[] localHeaders, int[] compressedSizes) throws IOException {
            int[] spans = new int[localHeaders.length];
            long total = 0;
            for (int i = 0; i < localHeaders.length; i++) {
                byte[] header = read(localHeaders[i], LOCAL_SIZE);
                if (int32(header, 0) != LOCAL_SIGNATURE) {
                    throw new IOException("Corrupt zip file (bad local header at " + localHeaders[i] + ")");
                }
                spans[i] = LOCAL_SIZE + int16(header, 26) + int16(header, 28) + compressedSizes[i];
                total += spans[i];
            }
            if (total > Integer.MAX_VALUE - 8) {
                throw new IOException("The entries to keep don't fit in one buffer");
            }
            byte[] bytes = new byte[(int) total];
            int[] starts = new int[localHeaders.length];
            int at = 0;
            for (int i = 0; i < localHeaders.length; i++) {
                System.arraycopy(read(localHeaders[i], spans[i]), 0, bytes, at, spans[i]);
                starts[i] = at;
                at += spans[i];
            }
            return new Kept(new Bytes(bytes), starts);
        }
    }

    /** What {@link Source#keep} copied, and where each entry's local header now starts. */
    static final class Kept {
        private final Source source;
        private final int[] starts;

        Kept(Source source, int[] starts) {
            this.source = source;
            this.starts = starts;
        }

        Source source() {
            return source;
        }

        /** Where the {@code i}th entry kept now starts. */
        int start(int i) {
            return starts[i];
        }
    }

    /** A file in the zip, as the central directory describes it. */
    record Entry(String name, int method, int compressedSize, int size, int localHeader) {}

    private static final int END_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int END_SIZE = 22;
    private static final int CENTRAL_SIZE = 46;
    private static final int LOCAL_SIZE = 30;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;

    private final Source source;
    private final List<Entry> entries;

    private ZipArchive(Source source, List<Entry> entries) {
        this.source = source;
        this.entries = entries;
    }

    /** Reads the central directory; fails if {@code source} isn't a zip this class can read. */
    static ZipArchive open(Source source) throws IOException {
        int size = source.size();
        int tailLength = Math.min(size, END_SIZE + MAX_COMMENT);
        byte[] tail = source.read(size - tailLength, tailLength);
        int end = -1;
        // The end record sits before a comment of up to 64 KiB; search back for its signature.
        for (int i = tailLength - END_SIZE; i >= 0; i--) {
            if (int32(tail, i) == END_SIGNATURE && i + END_SIZE + int16(tail, i + 20) == tailLength) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IOException("Not a zip file (no end of central directory)");
        }
        int count = int16(tail, end + 10);
        long directorySize = uint32(tail, end + 12);
        long directoryStart = uint32(tail, end + 16);
        if (count == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryStart == 0xFFFFFFFFL) {
            throw new IOException("ZIP64 archives aren't supported");
        }
        if (directoryStart + directorySize > size) {
            throw new IOException("Truncated zip file (central directory past the end)");
        }
        byte[] directory = source.read((int) directoryStart, (int) directorySize);
        List<Entry> entries = new ArrayList<>(count);
        int at = 0;
        for (int i = 0; i < count; i++) {
            if (at + CENTRAL_SIZE > directory.length || int32(directory, at) != CENTRAL_SIGNATURE) {
                throw new IOException("Corrupt zip file (bad central directory entry " + i + ")");
            }
            int flags = int16(directory, at + 8);
            int method = int16(directory, at + 10);
            long compressedSize = uint32(directory, at + 20);
            long entrySize = uint32(directory, at + 24);
            int nameLength = int16(directory, at + 28);
            int extraLength = int16(directory, at + 30);
            int commentLength = int16(directory, at + 32);
            long localHeader = uint32(directory, at + 42);
            if (at + CENTRAL_SIZE + nameLength > directory.length) {
                throw new IOException("Corrupt zip file (name past the central directory)");
            }
            // Names are UTF-8 when flag bit 11 says so, and in practice ASCII otherwise.
            String name = new String(directory, at + CENTRAL_SIZE, nameLength, StandardCharsets.UTF_8);
            if ((flags & 1) != 0) {
                throw new IOException("Encrypted zip entries aren't supported: " + name);
            }
            if (compressedSize == 0xFFFFFFFFL || entrySize == 0xFFFFFFFFL || localHeader == 0xFFFFFFFFL) {
                throw new IOException("ZIP64 entries aren't supported: " + name);
            }
            entries.add(new Entry(name, method, (int) compressedSize, (int) entrySize, (int) localHeader));
            at += CENTRAL_SIZE + nameLength + extraLength + commentLength;
        }
        return new ZipArchive(source, List.copyOf(entries));
    }

    List<Entry> entries() {
        return entries;
    }

    /**
     * The entries {@code include} takes, copied out of this zip into an array of their own, still
     * compressed (each entry's local header and data, the offsets renumbered), or this archive if it
     * takes them all. Whoever reads only some of a large zip's files keeps only those, and the zip's
     * own buffer can go: the client jar's classes, most of its 40 MB, are never read in a browser.
     */
    ZipArchive compact(Predicate<String> include) throws IOException {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) {
            if (include.test(entry.name())) {
                kept.add(entry);
            }
        }
        if (kept.size() == entries.size()) {
            // Everything is kept: nothing to copy out.
            return this;
        }
        int[] localHeaders = new int[kept.size()];
        int[] compressedSizes = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            localHeaders[i] = kept.get(i).localHeader();
            compressedSizes[i] = kept.get(i).compressedSize();
        }
        Kept copy = source.keep(localHeaders, compressedSizes);
        List<Entry> moved = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            Entry entry = kept.get(i);
            moved.add(new Entry(entry.name(), entry.method(), entry.compressedSize(), entry.size(), copy.start(i)));
        }
        return new ZipArchive(copy.source(), List.copyOf(moved));
    }

    /** A zip's bytes, or a compacted part of them, in an array. */
    private static final class Bytes implements Source {
        private final byte[] bytes;

        Bytes(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int size() {
            return bytes.length;
        }

        @Override
        public byte[] read(int position, int length) throws IOException {
            if (position < 0 || length < 0 || position > bytes.length - length) {
                throw new IOException("Read past the end of the zip");
            }
            byte[] out = new byte[length];
            System.arraycopy(bytes, position, out, 0, length);
            return out;
        }
    }

    /** An entry's uncompressed bytes. */
    byte[] read(Entry entry) throws IOException {
        byte[] header = source.read(entry.localHeader(), LOCAL_SIZE);
        if (int32(header, 0) != LOCAL_SIGNATURE) {
            throw new IOException("Corrupt zip file (bad local header for " + entry.name() + ")");
        }
        // The local header's name and extra field may differ in length from the central one's.
        int dataStart = entry.localHeader() + LOCAL_SIZE + int16(header, 26) + int16(header, 28);
        byte[] compressed = source.read(dataStart, entry.compressedSize());
        return switch (entry.method()) {
            case STORED -> compressed;
            case DEFLATED -> inflate(entry, compressed);
            default -> throw new IOException("Unsupported compression method " + entry.method() + ": " + entry.name());
        };
    }

    private static byte[] inflate(Entry entry, byte[] compressed) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[entry.size()];
            int done = 0;
            while (done < out.length) {
                int inflated = inflater.inflate(out, done, out.length - done);
                if (inflated == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                done += inflated;
            }
            if (done != out.length) {
                throw new IOException(
                        "Corrupt zip entry " + entry.name() + ": " + done + " of " + out.length + " bytes");
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("Corrupt zip entry " + entry.name(), e);
        } finally {
            inflater.end();
        }
    }

    private static int int16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    }

    private static int int32(byte[] bytes, int at) {
        return int16(bytes, at) | int16(bytes, at + 2) << 16;
    }

    private static long uint32(byte[] bytes, int at) {
        return int32(bytes, at) & 0xFFFFFFFFL;
    }
}
