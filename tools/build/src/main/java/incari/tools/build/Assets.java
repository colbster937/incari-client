package incari.tools.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The game's assets (sounds, music, languages, the panorama...) for the desktop runtime, in the
 * launcher's layout under {@code work/cache/assets}, so the game finds them with
 * {@code --assetsDir} and {@code --assetIndex} exactly as with Mojang's launcher.
 *
 * <p>About 480 MB for 26.3. Only the desktop runtime needs them; setup and CI don't download them.
 */
public final class Assets {

    static final URI OBJECT_HOST = URI.create("https://resources.download.minecraft.net/");

    private Assets() {}

    /** One asset: where the game looks for it, and its content hash (also its file name). */
    record AssetObject(String name, String hash, long size) {
        String objectPath() {
            return hash.substring(0, 2) + "/" + hash;
        }
    }

    /** Downloads the version's asset index and every object it lists that isn't cached yet. */
    public static String fetch(Workspace ws, BuildConfig config, Downloader downloader)
            throws IOException, InterruptedException {
        GameFiles game = GameFiles.load(ws, config);
        MojangMetadata.AssetIndex index = game.version().assetIndex();
        if (index == null) {
            throw new CliException("The version JSON for " + game.version().id() + " has no asset index");
        }
        Path indexFile = ws.assetsDir().resolve("indexes").resolve(index.id() + ".json");
        downloader.fetch(index.download().url(), index.download().sha1(), indexFile);

        List<AssetObject> objects = parseIndex(Files.readString(indexFile));
        Path objectsDir = ws.assetsDir().resolve("objects");
        long totalBytes = objects.stream().mapToLong(AssetObject::size).sum();
        Log.step("Downloading " + objects.size() + " assets (" + totalBytes / (1024 * 1024)
                + " MB, cached ones are skipped)");
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<?>> pending = new ArrayList<>();
            for (AssetObject object : objects) {
                pending.add(pool.submit(() -> {
                    downloader.fetch(
                            OBJECT_HOST.resolve(object.objectPath()),
                            object.hash(),
                            objectsDir.resolve(object.objectPath()));
                    return null;
                }));
            }
            for (Future<?> future : pending) {
                Concurrency.join(future);
            }
        }
        return index.id();
    }

    /** The objects an index lists, one per distinct hash (many names share a file), sorted by hash. */
    static List<AssetObject> parseIndex(String indexJson) {
        JsonObject objects = JsonParser.parseString(indexJson).getAsJsonObject().getAsJsonObject("objects");
        Map<String, AssetObject> byHash = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            if (!hash.matches("[0-9a-f]{40}")) {
                throw new CliException("Asset " + entry.getKey() + " has an invalid hash: " + hash);
            }
            byHash.putIfAbsent(
                    hash,
                    new AssetObject(entry.getKey(), hash, object.get("size").getAsLong()));
        }
        return List.copyOf(byHash.values());
    }
}
