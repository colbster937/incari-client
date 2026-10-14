package incari.framework.config;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Where the module config lives: small text files by relative path ({@code profiles.json},
 * {@code profiles/PvP.json}). The client puts them under the game directory's {@code incari/modules},
 * which the browser build keeps in OPFS (ADR 0011): writes are to memory, stored behind.
 *
 * <p>Game thread. Must not block: the browser's files are in memory, so these return at once.
 */
public interface ConfigStorage {

    /** The file's text, or null if there's no such file. */
    @Nullable
    String read(String path) throws IOException;

    /** Writes the file whole, making its folder if needed. */
    void write(String path, String text) throws IOException;

    /** Deletes the file; nothing if it isn't there. */
    void delete(String path) throws IOException;
}
