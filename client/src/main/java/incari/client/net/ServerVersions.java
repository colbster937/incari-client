package incari.client.net;

import incari.via.IncariVia;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Which Minecraft version the game speaks to a server (ADR 0017). Each server in the list
 * has a choice, saved with it: automatic (the default) or one release. Automatic means the version
 * the server reports, or else the one an earlier join found; a server that doesn't say (Eagler
 * servers' MOTDs never do) is joined as the game's own version, and a refusal names the right one
 * ({@link VersionRetry}, ADR 0018). Via translates whenever the choice isn't the game's own version.
 */
public final class ServerVersions {

    /** The saved choice for "work it out". */
    public static final int AUTO = -1;

    /**
     * The server list's protocol for a server that didn't say which version it speaks (an Eagler
     * server): joinable, and "Auto" finds the version when it joins.
     */
    public static final int UNKNOWN = -2;

    private static final String TAG = "incariVersion";

    private ServerVersions() {}

    /** The game's own protocol. */
    public static int gameProtocol() {
        return SharedConstants.getCurrentVersion().protocolVersion();
    }

    /** {@code ServerData.write}: saves a non-automatic choice. */
    public static void write(ServerData server, CompoundTag tag) {
        if (server.incariVersion != AUTO) {
            tag.putInt(TAG, server.incariVersion);
        }
    }

    /** {@code ServerData.read}. */
    public static void read(ServerData server, CompoundTag tag) {
        server.incariVersion = tag.getIntOr(TAG, AUTO);
    }

    /**
     * Whether the game can join a server whose ping reported {@code protocol}: its own version, or
     * one Via translates. The server list marks the rest incompatible.
     */
    public static boolean isCompatible(int protocol) {
        return protocol == UNKNOWN || protocol == gameProtocol() || IncariVia.supports(protocol);
    }

    /** A version's name for the player: "1.8.x", "26.3". */
    public static String name(int protocol) {
        if (protocol == UNKNOWN) {
            return "Auto";
        }
        if (protocol == gameProtocol()) {
            return SharedConstants.getCurrentVersion().name();
        }
        String name = IncariVia.versionName(protocol);
        return name != null ? name : "protocol " + protocol;
    }

    /**
     * The edit-server screen's version button: shows the choice, automatic or one release, and
     * opens the list of them ({@link VersionSelectScreen}), which saves the pick into {@code server}.
     */
    public static Button button(ServerData server, int x, int y) {
        List<VersionSelectScreen.Choice> choices = new ArrayList<>();
        choices.add(new VersionSelectScreen.Choice(AUTO, "Auto", "the server's own version"));
        for (IncariVia.Release release : IncariVia.releases()) {
            String note = release.protocol() == gameProtocol() ? "native" : "";
            choices.add(new VersionSelectScreen.Choice(release.protocol(), release.name(), note));
        }
        if (choices.stream().noneMatch(choice -> choice.protocol() == server.incariVersion)) {
            server.incariVersion = AUTO;
        }
        return new VersionButton(server, x, y, pressed -> {
            Minecraft minecraft = Minecraft.getInstance();
            Screen editScreen = minecraft.gui.screen();
            if (editScreen != null) {
                minecraft.gui.setScreen(new VersionSelectScreen(editScreen, choices, server.incariVersion, protocol -> {
                    server.incariVersion = protocol;
                    pressed.setMessage(label(protocol));
                }));
            }
        });
    }

    /** The version button, knowing its server: the edit-server screen's edit puts Wispcraft's beside it. */
    static final class VersionButton extends Button.Plain {

        /** Its width as the game's layout places it: a whole row. */
        static final int FULL_WIDTH = 200;

        private final ServerData server;

        VersionButton(ServerData server, int x, int y, OnPress onPress) {
            super(x, y, FULL_WIDTH, 20, label(server.incariVersion), onPress, DEFAULT_NARRATION);
            this.server = server;
        }

        ServerData server() {
            return server;
        }
    }

    // Literal English until Incari has its own language files.
    private static Component label(int protocol) {
        return Component.literal("Version: " + (protocol == AUTO ? "Auto" : name(protocol)));
    }
}
