package incari.client.singleplayer;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.CrashReport;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.Stats;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Singleplayer's server in a worker (ADR 0039): one player, its owner, on a world of the player's
 * own, with no {@code Minecraft} beside it to read. What the integrated server reads from the
 * client (paused, view and simulation distance) the page sends ({@link WorldWorker}). It behaves as
 * the integrated server where a world would show it: it saves when the game pauses, the owner may
 * use commands when the world allows them, and the owner's player data goes into {@code level.dat},
 * so a world moves between the browser and the desktop unchanged. Published only by sharing it
 * through a relay (ADR 0062), whose guests the page hands over; a page can't
 * listen for connections.
 */
final class WorkerServer extends MinecraftServer {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The integrated server's least view and simulation distance. */
    private static final int MIN_DISTANCE = 2;

    private final Path gameDirectory;
    private final Consumer<CrashReport> crashed;
    private final SampleLogger tickTimes = new LocalSampleLogger(1);
    private volatile boolean clientPaused;
    private volatile boolean shared;
    private volatile int viewDistance;
    private volatile int simulationDistance;
    private boolean paused = true;
    private int appliedSimulationDistance;
    private boolean forceGameMode = true;

    WorkerServer(
            Thread serverThread,
            LevelStorageSource.LevelStorageAccess storage,
            PackRepository packRepository,
            WorldStem worldStem,
            Optional<GameRules> gameRules,
            DataFixer fixerUpper,
            Services services,
            LevelLoadListener loadListener,
            GameProfile owner,
            Path gameDirectory,
            int viewDistance,
            int simulationDistance,
            Consumer<CrashReport> crashed) {
        super(
                serverThread,
                storage,
                packRepository,
                worldStem,
                gameRules,
                Proxy.NO_PROXY,
                fixerUpper,
                services,
                loadListener,
                false,
                new NotificationManager());
        this.gameDirectory = gameDirectory;
        this.crashed = crashed;
        this.viewDistance = viewDistance;
        this.simulationDistance = simulationDistance;
        setSingleplayerProfile(owner);
        setPlayerList(new OwnerPlayerList(this, registries(), playerDataStorage));
    }

    private boolean guestCommandAccess;

    /** The page's game paused or carried on. */
    void setClientPaused(boolean paused) {
        clientPaused = paused;
    }

    /** The page's render and simulation distance, in chunks. */
    void setDistances(int view, int simulation) {
        viewDistance = view;
        simulationDistance = simulation;
    }

    // --- What World Options changes (the page asks, ADR 0039) ----------------------------------

    /** Whether the owner may use commands; game modes and permissions follow at once. */
    void setWorldAllowCommands(boolean allowCommands) {
        getWorldData().setAllowCommands(allowCommands);
        followWorldGameMode();
        sendPermissions();
    }

    /** The world's default game mode, which players follow if game modes are forced. */
    void setWorldGameType(GameType gameMode) {
        setDefaultGameType(gameMode);
        followWorldGameMode();
    }

    /** The owner's own game mode. */
    void setOwnerGameType(GameType gameMode) {
        GameProfile owner = getSingleplayerProfile();
        ServerPlayer player = owner == null ? null : getPlayerList().getPlayer(owner.id());
        if (player != null) {
            player.setGameMode(gameMode);
        }
    }

    boolean guestCommandAccess() {
        return guestCommandAccess;
    }

    void setGuestCommandAccess(boolean commandAccess) {
        guestCommandAccess = commandAccess;
        sendPermissions();
        followWorldGameMode();
    }

    /** Players without a game master's permission take the world's game mode, if game modes are forced. */
    private void followWorldGameMode() {
        if (!forceGameMode()) {
            return;
        }
        for (ServerPlayer player : getPlayerList().getPlayers()) {
            if (!player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
                player.setGameMode(getWorldData().getGameType());
            }
        }
    }

    private void sendPermissions() {
        for (ServerPlayer player : getPlayerList().getPlayers()) {
            getPlayerList().sendPlayerPermissionLevel(player);
        }
    }

    @Override
    protected boolean initServer() {
        LOGGER.info(
                "Starting singleplayer server version {} in a worker",
                SharedConstants.getCurrentVersion().name());
        setUsesAuthentication(false);
        loadLevel();
        GameProfile owner = getSingleplayerProfile();
        String levelName = getWorldData().getLevelName();
        setMotd(owner != null ? owner.name() + " - " + levelName : levelName);
        saveEverything(false, true, true);
        incari.client.world.RemoteTerrain.start(this);
        return true;
    }

    @Override
    protected void stopServer() {
        super.stopServer();
        incari.client.world.RemoteTerrain.stop();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    protected void tickServer(BooleanSupplier haveTime) {
        long start = System.nanoTime();
        tickOrPause(haveTime);
        // Each tick's time for the singleplayer benchmark: the worker has no frames to carry it.
        ClientPlatform.get()
                .profiling()
                .serverTickProfiled(
                        (System.nanoTime() - start) / 1_000_000.0,
                        paused,
                        overworld().getChunkSource().getLoadedChunksCount());
    }

    private void tickOrPause(BooleanSupplier haveTime) {
        boolean wasPaused = paused;
        // A shared world runs on for its guests while the owner's menu is open, as a LAN world does.
        paused = (clientPaused && !shared) || getPlayerList().getPlayers().isEmpty();
        if (!wasPaused && paused) {
            LOGGER.info("Saving and pausing game...");
            saveEverything(false, false, false);
        }
        if (paused) {
            // Only the connection: packets are still read and answered while the game is paused.
            tickConnection();
            for (ServerPlayer player : getPlayerList().getPlayers()) {
                player.awardStat(Stats.TOTAL_WORLD_TIME);
            }
            return;
        }
        if (wasPaused) {
            forceGameTimeSynchronization();
        }
        super.tickServer(haveTime);
        int view = Math.max(MIN_DISTANCE, viewDistance);
        if (view != getPlayerList().getViewDistance()) {
            LOGGER.info(
                    "Changing view distance to {}, from {}",
                    view,
                    getPlayerList().getViewDistance());
            getPlayerList().setViewDistance(view);
        }
        int simulation = Math.max(MIN_DISTANCE, simulationDistance);
        if (simulation != appliedSimulationDistance) {
            LOGGER.info("Changing simulation distance to {}, from {}", simulation, appliedSimulationDistance);
            getPlayerList().setSimulationDistance(simulation);
            appliedSimulationDistance = simulation;
        }
    }

    /**
     * The world shared through a relay (ADR 0062): guests may join, the game no longer pauses for
     * the owner's menus, and guests' packets are compressed (they cross the internet).
     */
    void setShared(boolean on) {
        shared = on;
        LOGGER.info(on ? "The world is shared: guests may join" : "The world is no longer shared");
    }

    @Override
    public int getMaxPlayers() {
        // The owner alone, but for a shared world (ADR 0062), as a LAN world's eight.
        return shared ? 8 : 1;
    }

    @Override
    public int getCompressionThreshold() {
        // One machine: compressing every packet would only cost both ends time. A guest of a
        // shared world logs in while it's shared and gets the game's usual threshold.
        return shared ? 256 : -1;
    }

    @Override
    protected SampleLogger getTickTimeLogger() {
        return tickTimes;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }

    @Override
    public boolean shouldRconBroadcast() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return true;
    }

    @Override
    public Path getServerDirectory() {
        return gameDirectory;
    }

    // One player, its owner: no limits on how fast it chats, runs commands or sends packets.

    @Override
    public int getChatSpamThresholdSeconds() {
        return 0;
    }

    @Override
    public int getCommandSpamThresholdSeconds() {
        return 0;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    // A page's worker: no dedicated server, and no native sockets.

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    protected void onServerCrash(CrashReport report) {
        crashed.accept(report);
    }

    @Override
    public SystemReport fillServerSystemReport(SystemReport systemReport) {
        systemReport.setDetail("Type", "Singleplayer server in a worker (Incari)");
        return systemReport;
    }

    @Override
    public boolean isPublished() {
        return shared;
    }

    @Override
    public LevelBasedPermissionSet operatorUserPermissions() {
        return LevelBasedPermissionSet.GAMEMASTER;
    }

    @Override
    public PermissionSet getFunctionCompilationPermissions() {
        return LevelBasedPermissionSet.GAMEMASTER;
    }

    @Override
    public boolean isSingleplayerOwner(NameAndId nameAndId) {
        GameProfile owner = getSingleplayerProfile();
        return owner != null && nameAndId.name().equalsIgnoreCase(owner.name());
    }

    @Override
    public LevelBasedPermissionSet getProfilePermissions(NameAndId nameAndId) {
        // The owner has every permission while the world allows commands (the list counts it an
        // operator then), as on the integrated server.
        if (isSingleplayerOwner(nameAndId) && getPlayerList().isOp(nameAndId)) {
            return LevelBasedPermissionSet.OWNER;
        }
        return super.getProfilePermissions(nameAndId);
    }

    @Override
    public boolean forceSynchronousWrites() {
        return false;
    }

    @Override
    public @Nullable GameType getForcedGameType() {
        return null;
    }

    @Override
    public boolean forceGameMode() {
        return forceGameMode;
    }

    @Override
    public void setForceGameMode(boolean forceGameMode) {
        this.forceGameMode = forceGameMode;
        followWorldGameMode();
    }

    /** The player list of a world with one player: the owner can't log in twice. */
    private static final class OwnerPlayerList extends PlayerList {

        OwnerPlayerList(
                WorkerServer server, LayeredRegistryAccess<RegistryLayer> registries, PlayerDataStorage playerData) {
            super(server, registries, playerData, server.notificationManager());
            setViewDistance(10);
        }

        @Override
        public @Nullable Component canPlayerLogin(java.net.SocketAddress address, NameAndId nameAndId) {
            if (getServer().isSingleplayerOwner(nameAndId) && getPlayerByName(nameAndId.name()) != null) {
                return Component.translatable("multiplayer.disconnect.name_taken");
            }
            return super.canPlayerLogin(address, nameAndId);
        }
    }
}
