package incari.platform.web.storage;

import java.io.IOException;

/** A file in the page's file system: its bytes in memory, or an entry of a mounted zip. */
abstract class FileNode extends Node {

    FileNode(String name, long lastModified) {
        super(name, lastModified);
    }

    @Override
    final boolean isDirectory() {
        return false;
    }

    abstract int length();

    /** The file's bytes for reading; mounted entries are inflated here. */
    abstract Bytes contents() throws IOException;

    /** A file's bytes: the first {@code size} of {@code data}. */
    @SuppressWarnings("ArrayRecordComponent") // Carries bytes; never compared or hashed.
    record Bytes(byte[] data, int size) {}
}
