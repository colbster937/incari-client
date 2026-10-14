package incari.tools.build;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The pinned game version from {@code incari.properties} at the repo root.
 *
 * <p>Pinning the hashes, not just the version id, matters because patches are text diffs
 * against decompiled output: if Mojang ever re-uploaded a jar under the same id, unpinned
 * builds would silently drift apart.
 */
public record BuildConfig(String minecraftVersion, String versionJsonSha1, String clientSha1) {

    public static final String FILE_NAME = "incari.properties";

    public static BuildConfig load(Path repoRoot) throws IOException {
        Path file = repoRoot.resolve(FILE_NAME);
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            props.load(reader);
        }
        return new BuildConfig(
                require(props, file, "minecraft.version"),
                require(props, file, "minecraft.versionJson.sha1"),
                require(props, file, "minecraft.client.sha1"));
    }

    private static String require(Properties props, Path file, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new CliException("Missing '" + key + "' in " + file);
        }
        return value.trim();
    }
}
