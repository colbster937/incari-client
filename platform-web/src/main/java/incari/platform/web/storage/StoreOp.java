package incari.platform.web.storage;

import org.jspecify.annotations.Nullable;

/**
 * One change for the persistent store, as the {@link Journal} collects them at a flush.
 *
 * @param path the path in the page's file system ("/incari/game/options.txt")
 * @param size for a write, the file's size afterwards
 * @param at for a write, where {@code data} goes in the file
 * @param data for a write, the bytes written since the last flush (all of them for a new file);
 *     null when only the size or time changed
 * @param lastModified the file's or folder's time, milliseconds since the epoch
 */
@SuppressWarnings("ArrayRecordComponent") // Carries bytes; never compared or hashed.
record StoreOp(Kind kind, String path, int size, int at, byte @Nullable [] data, long lastModified) {

    enum Kind {
        /** A folder exists at the path (replacing a file there). */
        MKDIR,
        /** A file exists at the path, with this size and these bytes at {@code at}. */
        WRITE,
        /** Nothing exists at the path any more, folders with everything in them. */
        DELETE
    }

    static StoreOp mkdir(String path, long lastModified) {
        return new StoreOp(Kind.MKDIR, path, 0, 0, null, lastModified);
    }

    static StoreOp write(String path, int size, int at, byte @Nullable [] data, long lastModified) {
        return new StoreOp(Kind.WRITE, path, size, at, data, lastModified);
    }

    static StoreOp delete(String path) {
        return new StoreOp(Kind.DELETE, path, 0, 0, null, 0);
    }
}
