package incari.tools.build;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where everything lives. Two roots: the repo (tracked, Incari-owned files only) and
 * {@code work/} inside it (git-ignored, everything derived from Mojang's jar).
 *
 * <p>Nothing outside {@code work/} may ever hold Mojang content. Every path that does is
 * defined here, so that rule can be checked in one place.
 */
public record Workspace(Path repoRoot) {

    /** The client tree's Java sources, relative to {@link #clientTree()}. */
    public static final String CLIENT_SOURCE_DIR = "src/main/java";

    public Workspace {
        repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    /**
     * Finds the repo root by walking up from {@code start} to the directory holding
     * {@code incari.properties}. Lets the CLI run from any subdirectory.
     */
    public static Workspace discover(Path start) {
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(BuildConfig.FILE_NAME))) {
                return new Workspace(dir);
            }
        }
        throw new CliException("Not inside an Incari checkout (no " + BuildConfig.FILE_NAME + " found above "
                + start.toAbsolutePath() + ")");
    }

    // --- Tracked inputs ---

    public Path patchesDir() {
        return repoRoot.resolve("patches");
    }

    public Path classManifest() {
        return patchesDir().resolve(ClassManifest.FILE_NAME);
    }

    // --- Generated, git-ignored ---

    public Path workDir() {
        return repoRoot.resolve("work");
    }

    public Path cacheDir() {
        return workDir().resolve("cache");
    }

    public Path versionManifestFile() {
        return cacheDir().resolve("version_manifest_v2.json");
    }

    public Path versionDir(String version) {
        return cacheDir().resolve("versions").resolve(version);
    }

    public Path versionJson(String version) {
        return versionDir(version).resolve(version + ".json");
    }

    public Path clientJar(String version) {
        return versionDir(version).resolve("client.jar");
    }

    /**
     * Repo-relative paths of the platform-neutral library jars, one per line. Written by
     * {@code fetch} for Gradle, which compiles the client tree against them.
     */
    public Path classpathList(String version) {
        return versionDir(version).resolve("classpath.txt");
    }

    /** Like {@link #classpathList}, for this machine's native library jars (desktop runtime only). */
    public Path nativesList(String version) {
        return versionDir(version).resolve("natives.txt");
    }

    /** The launcher's asset layout: {@code indexes/<id>.json} and {@code objects/<ab>/<hash>}. */
    public Path assetsDir() {
        return cacheDir().resolve("assets");
    }

    public Path librariesDir() {
        return cacheDir().resolve("libraries");
    }

    /** Pristine Vineflower output. Never edited; the client tree is copied from it. */
    public Path decompiledDir(String version) {
        return workDir().resolve("decompiled").resolve(version);
    }

    /** The git repo people edit: vendor commit, then the class manifest, then one commit per patch. */
    public Path clientTree() {
        return workDir().resolve("client");
    }

    public Path surveyDir(String version) {
        return workDir().resolve("survey").resolve(version);
    }
}
