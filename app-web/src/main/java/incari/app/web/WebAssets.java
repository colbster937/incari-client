package incari.app.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import incari.client.audio.RemoteSounds;
import incari.client.resources.RemoteAssets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's files from the asset index, laid out by name under the assets directory (the layout
 * the game reads when it's given no {@code --assetIndex}), where the vanilla pack finds them.
 *
 * <p>The index is hundreds of megabytes, so little is downloaded up front. At start: the sound list
 * ({@code sounds.json}), the pack's metadata ({@code pack.mcmeta}, which lists the languages and
 * is read before the first reload) and the built-in packs' zips (ADR 0055). The sounds get an empty placeholder each, so the game knows they
 * exist, and {@link RemoteSounds} fetches one the first time it plays (ADR 0013). The
 * Unicode font and the selected language are fetched by {@link RemoteAssets} before
 * each resource reload that reads them (ADR 0037). The page gets all of it from next to itself: the
 * index at {@value #INDEX_URL} and its objects under {@value #OBJECTS_URL}, which the dev server
 * serves from {@code ./incari assets}' download and {@code ./incari build} copies into its folder.
 *
 * <p>Not the launcher's layout with an index: the game reads that through its own link file
 * system, which TeaVM's {@code java.nio.file} can't walk.
 */
final class WebAssets {

    static final String INDEX_URL = "asset-index.json";
    static final String OBJECTS_URL = "assets/objects/";

    /** Fetched before the game starts, because it reads them while loading. */
    private static final List<String> AT_START = List.of("minecraft/sounds.json", "pack.mcmeta");

    /**
     * The built-in packs (Programmer Art, High Contrast; 0.7 MB), fetched at start too: the pack
     * repository lists them once, before the first reload (ADR 0055).
     */
    private static final List<String> BUILT_IN_PACKS = List.of("minecraft/resourcepacks/");

    /** Tries for each sound's download before it plays silent (and is asked for again later). */
    private static final int SOUND_ATTEMPTS = 3;

    private static final String HINT = "is the dev server serving the assets?";

    private WebAssets() {}

    /**
     * Stores the files read at start and a placeholder for every sound, and installs the fetchers
     * for the rest. Without an asset index the page runs silent and in
     * English only.
     */
    static void install(String assetsDirectory) {
        double start = Fetch.now();
        byte[] indexFile;
        try {
            indexFile = Fetch.bytes(INDEX_URL, "has ./incari assets downloaded the asset index?");
        } catch (IOException e) {
            System.out.println("[Incari] No asset index, so no sounds: " + e.getMessage());
            return;
        }
        try {
            File root = new File(assetsDirectory);
            JsonObject objects = JsonParser.parseString(new String(indexFile, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("objects");
            Map<String, String> sounds = new HashMap<>();
            Map<String, String> others = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String name = entry.getKey();
                String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
                if (name.endsWith(".ogg")) {
                    sounds.put(name, hash);
                    File placeholder = new File(root, name);
                    if (!placeholder.exists()) {
                        write(placeholder, new byte[0]);
                    }
                } else {
                    others.put(name, hash);
                }
            }
            Index index = new Index(root, others);
            index.fetch(AT_START);
            try {
                index.fetch(BUILT_IN_PACKS);
            } catch (IOException e) {
                // Sounds and languages still work; the Resource Packs screen lists no built-in packs.
                System.out.println("[Incari] No built-in resource packs: " + e.getMessage());
            }
            RemoteSounds.install(name -> {
                String hash = sounds.get(name);
                // A sound fetched as it first plays meets whatever the network is doing then.
                return hash == null ? null : Fetch.bytesRetrying(objectUrl(hash), HINT, SOUND_ATTEMPTS);
            });
            RemoteAssets.install(index::fetch);
            System.out.println("[Incari] Assets: " + sounds.size() + " sounds listed in "
                    + Math.round(Fetch.now() - start) + " ms");
        } catch (IOException | RuntimeException e) {
            System.out.println("[Incari] Couldn't set up the assets, so no sounds: " + e);
        }
    }

    /** The index's files other than sounds, written under {@code root} as they're fetched. */
    private record Index(File root, Map<String, String> hashes) {

        /**
         * Downloads, side by side, each of {@code assets} (names, or folders ending in {@code /})
         * that isn't written yet, and writes it. Suspends the calling coroutine: the entry point at
         * start, a background thread before a reload.
         */
        void fetch(List<String> assets) throws IOException {
            double start = Fetch.now();
            List<String> names = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                String name = entry.getKey();
                if (wanted(assets, name) && !new File(root, name).exists()) {
                    names.add(name);
                    urls.add(objectUrl(entry.getValue()));
                }
            }
            if (names.isEmpty()) {
                return;
            }
            byte[][] files = Fetch.all(urls, HINT);
            long bytes = 0;
            for (int i = 0; i < files.length; i++) {
                write(new File(root, names.get(i)), files[i]);
                bytes += files[i].length;
            }
            System.out.println("[Incari] Assets: fetched " + names.size() + " files (" + bytes / 1024 + " KB) in "
                    + Math.round(Fetch.now() - start) + " ms");
        }

        private static boolean wanted(List<String> assets, String name) {
            for (String asset : assets) {
                if (asset.endsWith("/") ? name.startsWith(asset) : name.equals(asset)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String objectUrl(String hash) {
        return OBJECTS_URL + hash.substring(0, 2) + "/" + hash;
    }

    private static void write(File file, byte[] contents) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Can't create " + parent);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(contents);
        }
    }
}
