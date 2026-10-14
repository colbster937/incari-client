package incari.tools.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The pinned client jar and its platform-neutral libraries, cached under {@code work/cache}.
 *
 * <p>{@link #fetch} downloads what's missing, plus this machine's native libraries for the
 * desktop runtime. {@link #load} only reads the cache, for steps (decompile, survey) that must
 * not touch the network.
 */
public record GameFiles(MojangMetadata.Version version, Path clientJar, List<LibraryJar> libraries) {

    /** A platform-neutral library and where its jar is cached. */
    public record LibraryJar(MojangMetadata.Library library, Path jar) {}

    public static GameFiles fetch(Workspace ws, BuildConfig config, Downloader downloader)
            throws IOException, InterruptedException {
        String id = config.minecraftVersion();
        Path versionJson = ws.versionJson(id);

        if (!Files.isRegularFile(versionJson) || !Downloader.sha1(versionJson).equals(config.versionJsonSha1())) {
            Log.step("Reading Mojang's version manifest");
            String manifest = downloader.fetchString(MojangMetadata.VERSION_MANIFEST);
            Files.createDirectories(ws.cacheDir());
            Files.writeString(ws.versionManifestFile(), manifest);
            MojangMetadata.Download listed = MojangMetadata.findVersion(manifest, id);
            if (!listed.sha1().equals(config.versionJsonSha1())) {
                throw new CliException("Mojang's metadata for " + id + " has SHA-1 " + listed.sha1()
                        + " but " + BuildConfig.FILE_NAME + " pins " + config.versionJsonSha1()
                        + ". Mojang changed a published version; review before re-pinning.");
            }
            downloader.fetch(listed.url(), listed.sha1(), versionJson);
        }

        MojangMetadata.Version version = MojangMetadata.parseVersion(Files.readString(versionJson));
        checkClientPin(version, config);

        Log.step("Downloading client " + id);
        downloader.fetch(version.client().url(), version.client().sha1(), ws.clientJar(id));

        List<MojangMetadata.Library> natives = hostNatives(version, NativePlatform.current());
        List<MojangMetadata.Library> libraries = new ArrayList<>(neutralLibraries(version));
        libraries.addAll(natives);
        Log.step("Downloading " + libraries.size() + " libraries (" + natives.size() + " native, for "
                + NativePlatform.current().classifier() + ")");
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> pending = new ArrayList<>();
            for (MojangMetadata.Library lib : libraries) {
                pending.add(pool.submit(() -> {
                    downloader.fetch(
                            lib.download().url(),
                            lib.download().sha1(),
                            ws.librariesDir().resolve(lib.path()));
                    return null;
                }));
            }
            for (Future<?> future : pending) {
                Concurrency.join(future);
            }
        }
        writeList(ws, ws.classpathList(id), neutralLibraries(version));
        writeList(ws, ws.nativesList(id), natives);
        return load(ws, config);
    }

    /** Reads the cache written by {@link #fetch}. Fails with a hint if it's missing. */
    public static GameFiles load(Workspace ws, BuildConfig config) throws IOException {
        String id = config.minecraftVersion();
        Path versionJson = ws.versionJson(id);
        Path clientJar = ws.clientJar(id);
        if (!Files.isRegularFile(versionJson) || !Files.isRegularFile(clientJar)) {
            throw new CliException("Minecraft " + id + " isn't downloaded yet. Run: ./incari fetch");
        }
        MojangMetadata.Version version = MojangMetadata.parseVersion(Files.readString(versionJson));
        checkClientPin(version, config);

        List<LibraryJar> libraries = new ArrayList<>();
        for (MojangMetadata.Library lib : neutralLibraries(version)) {
            Path jar = ws.librariesDir().resolve(lib.path());
            if (!Files.isRegularFile(jar)) {
                throw new CliException("Library " + lib.name() + " is missing. Run: ./incari fetch");
            }
            libraries.add(new LibraryJar(lib, jar));
        }
        return new GameFiles(version, clientJar, List.copyOf(libraries));
    }

    private static List<MojangMetadata.Library> neutralLibraries(MojangMetadata.Version version) {
        return version.libraries().stream()
                .filter(lib -> !lib.platformSpecific())
                .toList();
    }

    /** The native jars for {@code platform}, one per library that has them. */
    static List<MojangMetadata.Library> hostNatives(MojangMetadata.Version version, NativePlatform platform) {
        return version.libraries().stream()
                .filter(lib -> lib.classifier().equals(platform.classifier()))
                .toList();
    }

    /** Repo-relative jar paths, one per line: the format Gradle reads (see {@link Workspace#classpathList}). */
    private static void writeList(Workspace ws, Path file, List<MojangMetadata.Library> libraries) throws IOException {
        StringBuilder out = new StringBuilder();
        for (MojangMetadata.Library lib : libraries) {
            Path jar = ws.librariesDir().resolve(lib.path());
            out.append(ws.repoRoot().relativize(jar).toString().replace('\\', '/'))
                    .append('\n');
        }
        Files.writeString(file, out.toString());
    }

    private static void checkClientPin(MojangMetadata.Version version, BuildConfig config) {
        if (!version.client().sha1().equals(config.clientSha1())) {
            throw new CliException("Client jar for " + version.id() + " has SHA-1 "
                    + version.client().sha1() + " but " + BuildConfig.FILE_NAME + " pins " + config.clientSha1());
        }
    }
}
