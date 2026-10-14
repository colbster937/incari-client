package incari.app.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import incari.classlib.resources.ClassPathResources;
import incari.platform.web.storage.WebFileSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The asset pack (ADR 0014): what the build prepares for the game ahead of time, in one zip
 * the page mounts read-only at {@value #ROOT}: the translated shaders, and the vanilla sprites
 * decoded with their mip levels (ADR 0026). Like the client jar, the storage worker keeps it
 * (under its hash, so a new build's pack is fetched once) and it's mounted in place, each file
 * inflated when opened.
 *
 * <p>The pack holds translated Mojang files, so it's made on the machine that builds the game
 * ({@code :app-web:assetPack}) and never published.
 *
 * <p>Its layout: {@code pack.json} ({@code {"format": 1, "game": "26.3"}}), then the files by the
 * names the game's loaders read ({@code shaders.json}, {@code sprites.bin}), and under {@value #CLASSPATH} resources
 * libraries read from the class path (Via's mapping data).
 */
final class AssetPack {

    /** Where the pack's files appear in the page's file system. */
    static final String ROOT = "/incari-pack";

    /** The pack's directory of class-path resources. */
    static final String CLASSPATH = "classpath";

    /** The layout this code reads; a pack of another format is refused. */
    static final int FORMAT = 1;

    private AssetPack() {}

    /**
     * Mounts the build's pack, if the build manifest names one; suspends while it's fetched.
     *
     * @return whether a pack is mounted
     */
    static boolean mount(WebFileSystem files) throws IOException {
        String url = LoaderPage.packUrl();
        if (url == null) {
            return false;
        }
        double start = Fetch.now();
        WebFileSystem.Mounted mounted = files.mountZip(ROOT, url, cacheKey(url), name -> true);
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(Path.of(ROOT, "pack.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        int format = manifest.get("format").getAsInt();
        String game = manifest.get("game").getAsString();
        if (format != FORMAT || !game.equals(ClientJar.VERSION)) {
            throw new IOException("The asset pack is format " + format + " for " + game + "; this build reads format "
                    + FORMAT + " for " + ClientJar.VERSION);
        }
        ClassPathResources.addRoot(ROOT + "/" + CLASSPATH);
        System.out.println("[Incari] Asset pack: " + mounted.files() + " files, "
                + (mounted.cached() ? "from storage" : "downloaded") + ", in " + Math.round(Fetch.now() - start)
                + " ms");
        return true;
    }

    /** A file of the mounted pack, or null if there's no pack or it lacks the file. */
    static byte @Nullable [] read(String name) throws IOException {
        Path file = Path.of(ROOT, name);
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    /** The storage key: the pack's version (its URL's {@code ?v=}), so each build's is kept apart. */
    static String cacheKey(String url) {
        int version = url.indexOf("?v=");
        return "pack-" + (version < 0 ? "unversioned" : url.substring(version + 3));
    }
}
