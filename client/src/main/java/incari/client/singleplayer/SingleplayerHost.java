package incari.client.singleplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * What the World Options screen asks of the world's host: the integrated server's methods it calls,
 * by the same names (ADR 0039). The screen holds one of these instead of the {@code IntegratedServer}
 * itself, so the same screen serves a world whose server runs in a worker, where the host's state
 * comes from the worker and its changes go there ({@link WorkerWorlds}).
 */
public interface SingleplayerHost {

    /**
     * Hook ({@code WorldOptionsScreen}): the world's host, or null in multiplayer (the screen then
     * shows what any server's operator may change).
     */
    static @Nullable SingleplayerHost of(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            return new Integrated(server);
        }
        return WorkerWorlds.host(minecraft);
    }

    /** The world's settings the screen reads. */
    interface WorldSettings {
        GameType getGameType();

        boolean isAllowCommands();
    }

    WorldSettings getWorldData();

    boolean isSingleplayerOwner(NameAndId nameAndId);

    @Nullable
    GameType getPersonalGameMode();

    boolean isDemo();

    boolean isHardcore();

    boolean forceGameMode();

    void setForceGameMode(boolean forceGameMode);

    boolean getGuestCommandAccess();

    void setGuestCommandAccess(boolean commandAccess);

    MinecraftServer.MultiplayerScope getMultiplayerScope();

    int getPort();

    boolean publishServer(MinecraftServer.MultiplayerScope scope, int port);

    boolean unpublishServer();

    void setWorldAllowCommands(boolean allowCommands);

    void setWorldGameType(GameType gameMode);

    void setPersonalGameType(GameType gameMode);

    /** The integrated server itself, as the game has it. */
    final class Integrated implements SingleplayerHost {
        private final IntegratedServer server;

        Integrated(IntegratedServer server) {
            this.server = server;
        }

        @Override
        public WorldSettings getWorldData() {
            return new WorldSettings() {
                @Override
                public GameType getGameType() {
                    return server.getWorldData().getGameType();
                }

                @Override
                public boolean isAllowCommands() {
                    return server.getWorldData().isAllowCommands();
                }
            };
        }

        @Override
        public boolean isSingleplayerOwner(NameAndId nameAndId) {
            return server.isSingleplayerOwner(nameAndId);
        }

        @Override
        public @Nullable GameType getPersonalGameMode() {
            return server.getPersonalGameMode();
        }

        @Override
        public boolean isDemo() {
            return server.isDemo();
        }

        @Override
        public boolean isHardcore() {
            return server.isHardcore();
        }

        @Override
        public boolean forceGameMode() {
            return server.forceGameMode();
        }

        @Override
        public void setForceGameMode(boolean forceGameMode) {
            server.setForceGameMode(forceGameMode);
        }

        @Override
        public boolean getGuestCommandAccess() {
            return server.getGuestCommandAccess();
        }

        @Override
        public void setGuestCommandAccess(boolean commandAccess) {
            server.setGuestCommandAccess(commandAccess);
        }

        @Override
        public MinecraftServer.MultiplayerScope getMultiplayerScope() {
            return server.getMultiplayerScope();
        }

        @Override
        public int getPort() {
            return server.getPort();
        }

        @Override
        public boolean publishServer(MinecraftServer.MultiplayerScope scope, int port) {
            return server.publishServer(scope, port);
        }

        @Override
        public boolean unpublishServer() {
            return server.unpublishServer();
        }

        @Override
        public void setWorldAllowCommands(boolean allowCommands) {
            server.setWorldAllowCommands(allowCommands);
        }

        @Override
        public void setWorldGameType(GameType gameMode) {
            server.setWorldGameType(gameMode);
        }

        @Override
        public void setPersonalGameType(GameType gameMode) {
            server.setPersonalGameType(gameMode);
        }
    }
}
