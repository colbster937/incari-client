package incari.app.web;

import incari.classlib.concurrent.Waits;
import incari.classlib.resources.ClassPathResources;
import incari.client.datafix.DataFixing;
import incari.client.memory.LightCopies;
import incari.client.net.PacketHandoff;
import incari.client.net.PlatformEventLoopGroupHolder;
import incari.client.singleplayer.ChannelLink;
import incari.client.singleplayer.WorldWorker;
import incari.client.startup.CriterionCodecs;
import incari.client.world.BiomeCells;
import incari.client.world.ChunkCompression;
import incari.client.world.Collisions;
import incari.client.world.DensityBranches;
import incari.client.world.GoalFlags;
import incari.client.world.Heights;
import incari.client.world.PositionalRandoms;
import incari.client.world.RemoteTerrain;
import incari.client.world.SpawnCosts;
import incari.client.world.StrongholdRings;
import incari.client.world.SurfaceBuffers;
import incari.client.world.SurfaceWrites;
import incari.platform.api.Platforms;
import incari.platform.web.WebPlatform;
import incari.platform.web.WorkerPlatform;
import incari.platform.web.storage.WebFileSystem;
import java.io.IOException;
import java.util.List;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.ArrayBuffer;

/**
 * The entry point of a copy of the game in a worker (ADR 0039): {@code WebMain.main(["--worker",
 * role])}, as {@code game-worker.ts} starts it. It sets up what the role needs and nothing of the
 * client: a file system of its own (filled by the page), the client jar's resources, the headless
 * platform, and the settings the page makes that the server's code reads too.
 */
final class GameWorkerMain {

    /** Where the page's storage worker keeps the client jar, from the private file system's root. */
    private static final String CACHED_JAR = ".incari-cache/client-" + ClientJar.VERSION + ".jar";

    private GameWorkerMain() {}

    /** {@code query}: the page's query string, for the page options read here too. */
    static void main(String role, String query) throws IOException {
        LibraryDefaults.apply();
        // A background pool task that blocks gives the pool up, as on the page.
        Waits.beforeWait(WebPlatform::beforeWait);
        WebFileSystem files = WebFileSystem.installDetached(List.of(ClassPathResources.ROOT));
        int mounted = mountClientJar(files, !"all".equals(option(query, "workerjar")));
        System.out.println("[Incari] Worker (" + role + "): " + mounted + " of the client jar's resources mounted");
        Platforms.install(new WorkerPlatform(WebStorage.DEFAULT, files));
        PlatformEventLoopGroupHolder.useLocalEventLoops();
        // The page's defaults for what the server's code reads as well (WebMain).
        DataFixing.buildLazily();
        LightCopies.setEnabled(true);
        Collisions.setEnabled(true);
        PacketHandoff.setEnabled(true);
        StrongholdRings.setEnabled(!"game".equals(option(query, "rings")));
        SpawnCosts.setEnabled(!"game".equals(option(query, "spawncosts")));
        SpawnCosts.setVerify("verify".equals(option(query, "spawncosts")));
        SurfaceWrites.setEnabled(!"game".equals(option(query, "surface")));
        BiomeCells.setEnabled(!"game".equals(option(query, "biomecells")));
        BiomeCells.setVerify("verify".equals(option(query, "biomecells")));
        Heights.setEnabled(!"game".equals(option(query, "heights")));
        GoalFlags.setEnabled(!"game".equals(option(query, "goalflags")));
        DensityBranches.setEnabled(!"game".equals(option(query, "densitychoices")));
        RemoteTerrain.setHelpers(terrainHelpers(option(query, "terrainworkers")));
        CriterionCodecs.setEnabled(!"game".equals(option(query, "criteria")));
        ChunkCompression.setEnabled(!"game".equals(option(query, "deflate")));
        PositionalRandoms.setEnabled(!"game".equals(option(query, "randoms")));
        SurfaceBuffers.setEnabled(!"game".equals(option(query, "surfacebuffers")));
        // ADR 0041's packets a flush per message (?packets=each: one each, as before).
        ChannelLink.setBatched(!"each".equals(option(query, "packets")));
        switch (role) {
            case "server" -> WorldWorker.run();
            case "terrain" -> RemoteTerrain.serve();
            default -> throw new IllegalArgumentException("No such worker role: " + role);
        }
    }

    /**
     * Mounts the client jar's resources a worker reads, and returns how many. A server (or a
     * terrain helper) reads the data packs, the language file and the root files (the version, the
     * markers that find the vanilla pack's roots), never the client's assets: those stay out, and
     * the jar's buffer is let go once they're copied (ADR 0043). {@code ?workerjar=all} mounts
     * every resource, as before. The buffer is a local here so that it isn't held for the
     * program's life by {@code main}'s frame, which never returns.
     */
    private static int mountClientJar(WebFileSystem files, boolean serverOnly) throws IOException {
        ArrayBuffer jar = WorkerPlatform.cachedZip(CACHED_JAR, ClientJar.URL);
        return files.mountZip(
                ClassPathResources.ROOT, jar, serverOnly ? GameWorkerMain::serverResource : GameWorkerMain::resource);
    }

    /** Every resource of the jar: not a class, not its signature. */
    private static boolean resource(String name) {
        return !name.endsWith(".class") && !name.startsWith("META-INF/");
    }

    /** A resource a server reads: the data packs, the languages, and the files at the root. */
    private static boolean serverResource(String name) {
        return resource(name)
                && (name.startsWith("data/")
                        || name.startsWith("assets/minecraft/lang/")
                        || name.equals("assets/.mcassetsroot")
                        || name.indexOf('/') < 0);
    }

    /**
     * How many terrain helpers singleplayer's server starts (ADR 0042): the page's
     * {@code ?terrainworkers=N}, none otherwise. Off by default: no gain was measured where the
     * server keeps up, and where the page and the server have two cores between them a helper would
     * only take from both.
     */
    private static int terrainHelpers(String option) {
        if (option.isEmpty()) {
            return 0;
        }
        try {
            return Math.min(Integer.parseInt(option), Math.max(1, hardwareConcurrency() - 2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @JSBody(script = "return (self.navigator && self.navigator.hardwareConcurrency) || 1;")
    private static native int hardwareConcurrency();

    /** A parameter of the page's query string, or "" if it has none. */
    @JSBody(
            params = {"query", "name"},
            script = "return new URLSearchParams(query).get(name) || '';")
    private static native String option(String query, String name);
}
