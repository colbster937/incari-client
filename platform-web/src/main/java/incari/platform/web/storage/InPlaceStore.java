package incari.platform.web.storage;

import java.io.IOException;

/**
 * Where the tree's in-place files ({@link StoredFile}) keep their bytes (ADR 0041). The page reads
 * one whole when something opens it ({@link #opensInPlace} false); singleplayer's server in a
 * worker opens it where it is and reads and writes through the handle. Either may suspend (must
 * not be called from a JavaScript callback).
 */
interface InPlaceStore {

    /** Whether files are opened where they are ({@link #open}) rather than read whole ({@link #read}). */
    boolean opensInPlace();

    /** The stored file's bytes, all of them; empty if it isn't there. Suspends until read. */
    byte[] read(String path) throws IOException;

    /** Opens the stored file at {@code path} where it is, creating it (and its folders) if it isn't there. */
    Handle open(String path) throws IOException;

    /** An open in-place file: reads and writes go straight to where it's kept. One thread. */
    interface Handle {

        /** Reads up to {@code length} bytes from {@code at} into {@code buffer}; how many it read. */
        int read(byte[] buffer, int offset, int length, int at) throws IOException;

        /** Writes {@code length} bytes of {@code buffer} at {@code at}, growing the file if need be. */
        void write(byte[] buffer, int offset, int length, int at) throws IOException;

        int size() throws IOException;

        void truncate(int size) throws IOException;

        /** Makes what was written durable. */
        void flush() throws IOException;

        /** Flushes and lets the file go: another program may open it once this returns. */
        void close();
    }
}
