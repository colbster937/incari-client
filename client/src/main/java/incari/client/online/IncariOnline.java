package incari.client.online;

import com.mojang.logging.LogUtils;
import incari.client.modules.ModuleStorage;
import incari.client.platform.ClientPlatform;
import incari.online.OnlineService;
import incari.online.Social;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client's Incari account and its connection to the online service (ADR 0058). Accounts aren't
 * a module: there's no switch for them, and with no account, or no service, the game is as it was.
 *
 * <p>Connects once the title screen has shown (never while it loads), tells the service which server
 * the player is on as they join and leave, and hands it the tab list once a second. Game thread.
 */
public final class IncariOnline {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks between tab-list reads: a second. Names are looked up as they appear, not faster. */
    private static final int TAB_LIST_TICKS = 20;

    private static @Nullable String override;
    private static @Nullable OnlineService service;
    /** The connection the player was in a world on, as of the last tick. */
    private static @Nullable ClientPacketListener world;

    private static int tabListCountdown;

    /** A new account's recovery codes, not shown yet: the player left Create Account meanwhile. */
    private static @Nullable List<String> unsavedCodes;

    private IncariOnline() {}

    /**
     * A new account's recovery codes arrived after the player left Create Account. They're shown the
     * next time the account screen opens (shown once, they'd be lost if dropped), and a toast says so.
     */
    static void keepRecoveryCodes(List<String> codes) {
        unsavedCodes = List.copyOf(codes);
        Minecraft.getInstance()
                .gui
                .toastManager()
                .addToast(new ChatToast(new Social.Notice(
                        "Incari", false, "Account created", "Open Incari Account to save your recovery codes.")));
    }

    /** The recovery codes waiting to be shown, taken; null if there are none. */
    static @Nullable List<String> takeRecoveryCodes() {
        List<String> codes = unsavedCodes;
        unsavedCodes = null;
        return codes;
    }

    /**
     * Before the game starts: the service's URL in place of the built-in one (the page's
     * {@code ?online=}, the desktop's {@code -Dincari.online.url}), for testing against a local
     * service. An empty one turns accounts off.
     */
    public static void useUrl(String url) {
        override = url;
    }

    /** The service: made, not yet connected, the first time it's asked for. */
    public static OnlineService service() {
        OnlineService current = service;
        if (current == null) {
            Minecraft minecraft = Minecraft.getInstance();
            String url = override != null ? override : System.getProperty("incari.online.url", OnlineUrl.BUILT_IN);
            current = new OnlineService(
                    url,
                    "incari/" + SharedConstants.getCurrentVersion().name(),
                    ClientPlatform.get().network(),
                    ModuleStorage.folder(minecraft.gameDirectory.toPath(), "online"),
                    Util.backgroundExecutor(),
                    () -> ClientPlatform.get().threads().pauseIfLate(),
                    Util::getMillis,
                    Math::random,
                    (message, error) -> {
                        // Messages only, never a password, token or code: the service's code keeps
                        // them out, and the profiler and crash reports never see the channel.
                        if (error == null) {
                            LOGGER.warn(message);
                        } else {
                            LOGGER.warn("{}: {}", message, error.toString());
                        }
                    });
            service = current;
        }
        return current;
    }

    /** From {@code IncariTicks}, once a client tick. */
    public static void tick(Minecraft minecraft) {
        OnlineService online = service();
        if (!online.started() && (minecraft.gui.screen() instanceof TitleScreen || minecraft.level != null)) {
            online.start();
        }
        online.tick();
        trackWorld(minecraft, online);
        ChatMenu.tick(minecraft, online);
        UsageCounts.tick(online);
    }

    /**
     * Join and leave follow the connection, as the modules' do: a dimension change keeps it, a
     * transfer replaces it. Singleplayer and the player's own LAN world are never reported.
     */
    @SuppressWarnings("ReferenceEquality") // The same connection object, not an equal one.
    private static void trackWorld(Minecraft minecraft, OnlineService online) {
        ClientPacketListener connection = minecraft.level == null ? null : minecraft.getConnection();
        if (connection != world) {
            if (world != null) {
                online.left();
            }
            world = connection;
            ServerData server = minecraft.getCurrentServer();
            if (connection != null && server != null && !minecraft.isLocalServer()) {
                online.joined(server.ip, connection.getLocalGameProfile().name());
                tabListCountdown = 0;
            }
        }
        if (connection != null && online.inWorld() && --tabListCountdown <= 0) {
            tabListCountdown = TAB_LIST_TICKS;
            Map<UUID, String> players = new HashMap<>();
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                players.put(info.getProfile().id(), info.getProfile().name());
            }
            online.tabList(players);
        }
    }
}
