package incari.app.web;

import incari.classlib.resources.ClassPathResources;
import incari.platform.web.storage.WebFileSystem;
import java.io.IOException;

/**
 * Puts the client jar's resources where the game looks for them ({@link ClassPathResources}):
 * the build compiles the game's classes but not the rest of the jar, which is Mojang's to
 * distribute. The page fetches the jar the player's machine already has; for development that's
 * the one {@code ./incari setup} downloaded, which {@code :app-web:serve} offers at {@value #URL}.
 *
 * <p>The jar isn't unpacked: the storage worker keeps it (so later loads don't fetch
 * it again), and it's mounted read-only in place, each resource inflated when the game opens it.
 * Its bytes stay in the page, outside the game's heap (ADR 0011).
 */
final class ClientJar {

    static final String URL = "client.jar";

    /** The game version the jar is, which names its cached copy: a new version's replaces it. */
    static final String VERSION = "26.3";

    private ClientJar() {}

    /** Mounts every resource that isn't a class or jar metadata; suspends while it's fetched. */
    static void mount(WebFileSystem files) throws IOException {
        double start = Fetch.now();
        WebFileSystem.Mounted mounted = files.mountZip(
                ClassPathResources.ROOT,
                URL,
                "client-" + VERSION + ".jar",
                name -> !name.endsWith(".class") && !name.startsWith("META-INF/"));
        System.out.println("[Incari] Client jar: " + mounted.files() + " resources mounted, "
                + (mounted.cached() ? "from storage" : "downloaded") + ", in " + Math.round(Fetch.now() - start)
                + " ms");
    }
}
