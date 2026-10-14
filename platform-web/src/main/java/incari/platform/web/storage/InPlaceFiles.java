package incari.platform.web.storage;

/**
 * Which stored files stay where they are instead of being loaded into memory (ADR 0041): a world's
 * region files ({@code .mca}, and {@code .mcc} for a chunk too large for its region), the files
 * that grow with how much of a world has been explored. The page knows them by their size alone
 * and reads one only when something opens it; singleplayer's server in a worker reads and writes
 * them in the origin private file system with synchronous access handles. The storage worker
 * applies the same rule ({@code storage-worker.js}, told it at {@code open}).
 */
final class InPlaceFiles {

    /** A world's files are inside a folder of this name. */
    static final String WITHIN = "/saves/";

    private InPlaceFiles() {}

    /** Whether the file at {@code path} is kept in place. */
    static boolean matches(String path) {
        return path.contains(WITHIN) && (path.endsWith(".mca") || path.endsWith(".mcc"));
    }
}
