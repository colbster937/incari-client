package incari.via;

import com.viaversion.viabackwards.api.ViaBackwardsPlatform;
import com.viaversion.viaversion.ViaAPIBase;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.libs.fastutil.objects.ObjectLinkedOpenHashSet;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocol.ProtocolManagerImpl;
import io.netty.buffer.ByteBuf;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Via in the client: started on first use (joining a server of another version), then shared by
 * every connection. The game speaks its own protocol ({@link #nativeProtocol}); for each connection
 * Via is told the server's, and translates both ways.
 *
 * <p>ViaVersion covers older servers back to 1.8. ViaBackwards covers servers newer than the game
 * and loads only when needed (its mappings are memory the common case doesn't use).
 */
public final class IncariVia {

    private static final Logger LOGGER = Logger.getLogger("Via");

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile boolean backwardsLoaded;
    private static @Nullable File dataFolder;
    private static int nativeProtocol;

    private IncariVia() {}

    /**
     * Sets where Via may keep files and which protocol the game speaks. Call once, before {@link
     * #load}; cheap, loads nothing.
     */
    public static void configure(File folder, int gameProtocol) {
        synchronized (LOCK) {
            dataFolder = folder;
            nativeProtocol = gameProtocol;
        }
    }

    /** The protocol the game itself speaks. */
    public static int nativeProtocol() {
        return nativeProtocol;
    }

    /** Whether Via can take the game to a server speaking {@code protocol}. */
    public static boolean supports(int protocol) {
        return protocol == nativeProtocol || versionFor(protocol) != null;
    }

    /**
     * The release that speaks {@code protocol}, if Via knows it: "1.8.x", "1.20.3-1.20.4" and so on.
     * Needs no loading: the version table is plain data.
     */
    public static @Nullable String versionName(int protocol) {
        ProtocolVersion version = versionFor(protocol);
        return version == null ? null : version.getName();
    }

    /** Every release Via can join, newest first, as (protocol, name) pairs. */
    public static List<Release> releases() {
        List<Release> releases = new ArrayList<>();
        for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
            if (version.getVersionType() == VersionType.RELEASE
                    && version.getVersion() >= ProtocolVersion.v1_8.getVersion()) {
                releases.add(new Release(version.getVersion(), version.getName()));
            }
        }
        releases.sort((a, b) -> Integer.compare(b.protocol(), a.protocol()));
        return releases;
    }

    /**
     * The protocol of the release a server names in words, as a kick message does: "1.8.8",
     * "1.20.4", "26.3", or "1.21.x" (the newest 1.21 release Via knows). Returns -1 for a version
     * Via can't join.
     */
    public static int protocolNamed(String version) {
        boolean anyPatch = version.endsWith(".x");
        String prefix = anyPatch ? version.substring(0, version.length() - 1) : version;
        String minor = anyPatch ? version.substring(0, version.length() - 2) : version;
        int best = -1;
        for (ProtocolVersion candidate : ProtocolVersion.getProtocols()) {
            if (candidate.getVersionType() != VersionType.RELEASE
                    || candidate.getVersion() < ProtocolVersion.v1_8.getVersion()) {
                continue;
            }
            for (String included : candidate.getIncludedVersions()) {
                boolean matches =
                        anyPatch ? included.equals(minor) || included.startsWith(prefix) : included.equals(version);
                if (matches && candidate.getVersion() > best) {
                    best = candidate.getVersion();
                }
            }
        }
        return best;
    }

    /** A release a server can speak. */
    public record Release(int protocol, String name) {}

    private static @Nullable ProtocolVersion versionFor(int protocol) {
        if (!ProtocolVersion.isRegistered(VersionType.RELEASE, protocol)) {
            return null;
        }
        ProtocolVersion version = ProtocolVersion.getProtocol(VersionType.RELEASE, protocol);
        return version.getVersion() >= ProtocolVersion.v1_8.getVersion() ? version : null;
    }

    /**
     * Starts Via if it isn't running, with what a connection to {@code serverProtocol} needs. Loads
     * mapping data, so it takes a while the first time (seconds in the browser): call it off the
     * game's main thread, as the connect screen's connector thread does.
     *
     * @throws IllegalArgumentException if Via can't reach {@code serverProtocol}
     */
    public static void load(int serverProtocol) {
        ProtocolVersion target = versionFor(serverProtocol);
        if (target == null) {
            throw new IllegalArgumentException("Via can't translate to protocol " + serverProtocol);
        }
        synchronized (LOCK) {
            if (!loaded) {
                start();
                loaded = true;
            }
            if (serverProtocol > nativeProtocol && !backwardsLoaded) {
                LOGGER.info("Loading ViaBackwards for a server newer than the game (" + target.getName() + ")");
                new BackwardsPlatform();
                ((ProtocolManagerImpl) Via.getManager().getProtocolManager()).refreshVersions();
                backwardsLoaded = true;
            }
        }
        awaitMappings();
    }

    /**
     * Waits for Via's mapping data, which it loads on its own pool: the connection's handshake
     * would otherwise wait for it on the network thread.
     */
    private static void awaitMappings() {
        ProtocolManagerImpl protocols = (ProtocolManagerImpl) Via.getManager().getProtocolManager();
        // Propagating errors: a protocol whose data failed to load would fail mid-game instead.
        // (Via logs the failure too, but the browser's logging drops the exception.)
        while (!protocols.hasLoadedMappings() && !protocols.checkForMappingCompletion(true)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while Via loaded its mappings", e);
            }
        }
    }

    /** Stops Via's scheduler and connections, if Via was started. The game's exit. */
    public static void shutdown() {
        synchronized (LOCK) {
            if (loaded) {
                ((ViaManagerImpl) Via.getManager()).destroy();
                loaded = false;
            }
        }
    }

    private static void start() {
        File folder = dataFolder;
        if (folder == null) {
            throw new IllegalStateException("IncariVia.configure first");
        }
        long started = System.currentTimeMillis();
        Platform platform = new Platform(folder);
        ViaManagerImpl manager = ViaManagerImpl.builder()
                .platform(platform)
                .injector(new Injector())
                .loader(new Loader())
                .commandHandler(new ViaCommandHandler(false))
                .build();
        Via.init(manager);
        platform.getConf().reload();
        manager.init();
        manager.onServerLoaded();
        ProtocolManagerImpl protocols = (ProtocolManagerImpl) manager.getProtocolManager();
        // A client can take any path to a server, however long.
        protocols.setMaxProtocolPathSize(Integer.MAX_VALUE);
        protocols.setMaxPathDeltaIncrease(-1);
        protocols.refreshVersions();
        LOGGER.info(
                "Via " + platform.getPluginVersion() + " started in " + (System.currentTimeMillis() - started) + " ms");
    }

    /** Via's view of this client: a proxy in front of servers of every version. */
    private static final class Platform implements ViaPlatform<UserConnection> {

        private final File folder;
        private final ViaVersionConfig config;
        private final ViaAPI<UserConnection> api = new ViaAPIBase<>() {
            @Override
            public ProtocolVersion getPlayerProtocolVersion(UserConnection player) {
                return player.getProtocolInfo().protocolVersion();
            }

            @Override
            public void sendRawPacket(UserConnection player, ByteBuf packet) {
                player.scheduleSendRawPacket(packet);
            }
        };

        Platform(File folder) {
            this.folder = folder;
            this.config = new ViaSettings.ViaVersionConfig(new File(folder, "viaversion.yml"), LOGGER);
        }

        @Override
        public Logger getLogger() {
            return LOGGER;
        }

        @Override
        public String getPlatformName() {
            return "Incari";
        }

        @Override
        public String getPlatformVersion() {
            return "dev";
        }

        @Override
        public boolean isProxy() {
            return true;
        }

        @Override
        public ViaAPI<UserConnection> getApi() {
            return api;
        }

        @Override
        public ViaVersionConfig getConf() {
            return config;
        }

        @Override
        public File getDataFolder() {
            return folder;
        }
    }

    /** Nothing to inject: Incari adds Via to each connection's pipeline itself ({@link ViaPipeline}). */
    private static final class Injector implements ViaInjector {

        @Override
        public void inject() {}

        @Override
        public void uninject() {}

        @Override
        public ProtocolVersion getServerProtocolVersion() {
            return getServerProtocolVersions().first();
        }

        /** Every version: the server behind each connection is whichever one it names. */
        @Override
        public SortedSet<ProtocolVersion> getServerProtocolVersions() {
            SortedSet<ProtocolVersion> versions = new ObjectLinkedOpenHashSet<>();
            versions.addAll(ProtocolVersion.getProtocols());
            return versions;
        }

        @Override
        public String getEncoderName() {
            return ViaPipeline.ENCODER;
        }

        @Override
        public String getDecoderName() {
            return ViaPipeline.DECODER;
        }

        @Override
        public JsonObject getDump() {
            return new JsonObject();
        }
    }

    /** Registers the connection's server version with Via, in place of Via's own guess. */
    private static final class Loader implements ViaPlatformLoader {

        @Override
        public void load() {
            Via.getManager().getProviders().use(VersionProvider.class, new ServerVersions());
        }

        @Override
        public void unload() {}
    }

    private static final class BackwardsPlatform implements ViaBackwardsPlatform {

        BackwardsPlatform() {
            init(new ViaSettings.ViaBackwardsConfig(new File(getDataFolder(), "viabackwards.yml"), LOGGER));
            enable();
        }

        @Override
        public Logger getLogger() {
            return LOGGER;
        }

        @Override
        public boolean isOutdated() {
            return false;
        }

        @Override
        public void disable() {}

        @Override
        public File getDataFolder() {
            return Via.getPlatform().getDataFolder();
        }
    }
}
