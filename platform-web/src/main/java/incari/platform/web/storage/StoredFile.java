package incari.platform.web.storage;

import org.jspecify.annotations.Nullable;

/**
 * An in-place file ({@link InPlaceFiles}): its bytes stay in the store, and the tree knows its size
 * and time (ADR 0041). On the page, opening one reads it whole and puts a {@link DataFile} in its
 * place; in singleplayer's server worker, it's opened where it is ({@link #handle}) and stays
 * that way until the world is handed back.
 */
final class StoredFile extends FileNode {

    /** The file's size, as the store last said or as this program's writes left it. */
    int size;

    /** Where it's open, in a worker that opens files in place; null until first opened, and once closed. */
    InPlaceStore.@Nullable Handle handle;

    StoredFile(String name, long lastModified, int size) {
        super(name, lastModified);
        this.size = size;
    }

    @Override
    int length() {
        return size;
    }

    @Override
    Bytes contents() {
        // The tree reads a stored file through its store, never through here.
        throw new UnsupportedOperationException("An in-place file's bytes are in the store");
    }

    /** Lets its handle go, if it has one open. */
    void close() {
        InPlaceStore.Handle open = handle;
        handle = null;
        if (open != null) {
            open.close();
        }
    }
}
