package incari.tools.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The few fields Incari reads from Mojang's launcher metadata: the version manifest
 * (every version and where its JSON lives) and a version JSON (client jar and libraries).
 *
 * <p>Parsed by hand from a JSON tree rather than bound to classes, because the launcher
 * format has many fields we don't use and Mojang adds more over time.
 */
public final class MojangMetadata {

    public static final URI VERSION_MANIFEST =
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    private MojangMetadata() {}

    /** A download Mojang describes by URL and SHA-1. */
    public record Download(URI url, String sha1, long size) {}

    /** One library. {@code path} is the Maven-layout path, used as its cache location. */
    public record Library(String name, String path, Download download, boolean platformSpecific) {

        /** The Maven classifier ({@code natives-linux}), or empty for a platform-neutral jar. */
        public String classifier() {
            String[] parts = name.split(":", -1);
            return parts.length > 3 ? parts[3] : "";
        }
    }

    /** The game's asset index: which sounds, languages and other assets this version uses. */
    public record AssetIndex(String id, Download download) {}

    /** {@code assetIndex} is null only in test fixtures; every real version JSON has one. */
    public record Version(
            String id,
            Download client,
            int javaMajorVersion,
            List<Library> libraries,
            @Nullable AssetIndex assetIndex) {}

    /** Finds a version's JSON download in the manifest. */
    public static Download findVersion(String manifestJson, String versionId) {
        JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject version = element.getAsJsonObject();
            if (version.get("id").getAsString().equals(versionId)) {
                return new Download(
                        URI.create(version.get("url").getAsString()),
                        version.get("sha1").getAsString(),
                        -1);
            }
        }
        throw new CliException("Version " + versionId + " is not in Mojang's version manifest");
    }

    public static Version parseVersion(String versionJson) {
        JsonObject root = JsonParser.parseString(versionJson).getAsJsonObject();
        Download client = download(root.getAsJsonObject("downloads").getAsJsonObject("client"));
        int java = root.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();

        List<Library> libraries = new ArrayList<>();
        JsonArray libs = root.getAsJsonArray("libraries");
        for (JsonElement element : libs) {
            JsonObject lib = element.getAsJsonObject();
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) {
                continue;
            }
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            String name = lib.get("name").getAsString();
            // Natives carry a classifier ("group:artifact:version:natives-linux") and are only
            // useful to the desktop JVM, never to the browser build or the decompiler.
            boolean platformSpecific = name.split(":").length > 3;
            libraries.add(new Library(name, artifact.get("path").getAsString(), download(artifact), platformSpecific));
        }
        JsonObject assets = root.getAsJsonObject("assetIndex");
        AssetIndex assetIndex =
                assets == null ? null : new AssetIndex(assets.get("id").getAsString(), download(assets));
        return new Version(root.get("id").getAsString(), client, java, List.copyOf(libraries), assetIndex);
    }

    private static Download download(JsonObject object) {
        return new Download(
                URI.create(object.get("url").getAsString()),
                object.get("sha1").getAsString(),
                object.get("size").getAsLong());
    }
}
