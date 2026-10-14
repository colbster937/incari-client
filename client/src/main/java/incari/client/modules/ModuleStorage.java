package incari.client.modules;

import incari.framework.config.ConfigStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The module config's files, under the game directory's {@code incari/modules}; the online service's
 * session under {@code incari/online} ({@link #folder}). In the browser the
 * game directory is the page's in-memory file system, stored behind in OPFS or IndexedDB (ADR 0011),
 * so a write here returns at once.
 */
public final class ModuleStorage implements ConfigStorage {

    private final Path root;

    ModuleStorage(Path gameDirectory) {
        this(gameDirectory, "modules");
    }

    private ModuleStorage(Path gameDirectory, String folder) {
        this.root =
                gameDirectory.resolve("incari").resolve(folder).toAbsolutePath().normalize();
    }

    /** Small files under the game directory's {@code incari/<name>}. */
    public static ConfigStorage folder(Path gameDirectory, String name) {
        return new ModuleStorage(gameDirectory, name);
    }

    @Override
    public @Nullable String read(String path) throws IOException {
        Path file = resolve(path);
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    @Override
    public void write(String path, String text) throws IOException {
        Path file = resolve(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Override
    public void delete(String path) throws IOException {
        Files.deleteIfExists(resolve(path));
    }

    /** {@code path} under the root; profile names are checked, but a path out of it is refused anyway. */
    private Path resolve(String path) throws IOException {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Not under " + root + ": " + path);
        }
        return file;
    }
}
