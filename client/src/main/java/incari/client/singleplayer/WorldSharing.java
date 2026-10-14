package incari.client.singleplayer;

import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.client.settings.ClientSettings;
import incari.net.relay.RelayHost;
import incari.platform.api.net.NetConnection;
import incari.platform.api.threads.PlatformChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Sharing a singleplayer world through an Eagler relay (ADR 0062): World Options' LAN choice in the
 * browser, where a page can't listen for connections. The page shares the world on a relay
 * ({@link RelayHost}) and posts the join code in the chat; each guest's WebRTC data channel stays in
 * the page (workers have no WebRTC), and its messages cross to singleplayer's server in the worker,
 * which gives each guest a connection of its own ({@link GuestLink}). Guests join from Multiplayer,
 * Shared..., with the relay and the code; an Incari guest joins the world as it is (no Via).
 *
 * <p>The relays are Eaglercraft's public ones, tried in order until one gives a code; the list is
 * {@code relays} in {@code incari/settings.txt} (";"-separated).
 *
 * <p>Game thread, but for the worker's messages, which the session's reader hands over ({@link
 * #fromWorker}); the page runs one thread at a time.
 */
final class WorldSharing {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Eaglercraft's public relays, as its clients list them. */
    static final String DEFAULT_RELAYS =
            "wss://relay.deev.is/;wss://relay.lax1dude.net/;wss://relay.shhnowisnottheti.me/";

    private final PlatformChannel worker;
    private final String worldName;
    private final List<String> relays;
    private final Map<Integer, NetConnection> guests = new HashMap<>();
    private int relayIndex;
    private int nextGuest;
    private @Nullable RelayHost host;
    private boolean stopped;

    private WorldSharing(PlatformChannel worker, String worldName, List<String> relays) {
        this.worker = worker;
        this.worldName = worldName;
        this.relays = relays;
    }

    /** Whether this platform can share a world: WebRTC, which only the browser has. */
    static boolean supported() {
        return ClientPlatform.get().rtc().supported();
    }

    /** Starts sharing the world in {@code worker} on the first of {@code relays} that answers; null if this platform can't. */
    static @Nullable WorldSharing start(PlatformChannel worker, String worldName, List<String> relays) {
        if (!supported()) {
            return null;
        }
        WorldSharing sharing = new WorldSharing(worker, worldName, relays);
        worker.send(WorldMessages.SHARED, new byte[] {1}, 0, 1);
        sharing.tryNextRelay();
        return sharing;
    }

    /** The relays to share on, in order. */
    static List<String> relays() {
        String listed = ClientSettings.value("relays");
        List<String> relays = new ArrayList<>();
        for (String relay : (listed == null || listed.isBlank() ? DEFAULT_RELAYS : listed).split(";", -1)) {
            if (relay.startsWith("ws://") || relay.startsWith("wss://")) {
                relays.add(relay.strip());
            }
        }
        if (relays.isEmpty()) {
            relays.add(DEFAULT_RELAYS.split(";", -1)[0]);
        }
        return relays;
    }

    private void tryNextRelay() {
        if (stopped) {
            return;
        }
        if (relayIndex >= relays.size()) {
            say(Component.literal("Couldn't share the world: no relay responded")
                    .withStyle(ChatFormatting.RED));
            stop();
            return;
        }
        String relay = relays.get(relayIndex++);
        RelayHost created = new RelayHost(
                ClientPlatform.get().network(),
                ClientPlatform.get().rtc(),
                relay,
                worldName,
                SharedConstants.getCurrentVersion().protocolVersion(),
                new Listener(),
                System::currentTimeMillis);
        host = created;
        LOGGER.info("Sharing the world on {}", relay);
        created.start();
    }

    /** Once a client tick: the relay's timers. */
    void tick() {
        RelayHost current = host;
        if (current != null) {
            current.tick();
        }
    }

    /** The join code, once the relay has given one. */
    @Nullable
    String code() {
        RelayHost current = host;
        return current == null ? null : current.code();
    }

    /** Stops sharing: no one new joins. Guests already in play on. */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        RelayHost current = host;
        host = null;
        if (current != null) {
            current.close();
        }
        worker.send(WorldMessages.SHARED, new byte[] {0}, 0, 1);
    }

    /** The world closed: sharing stops and every guest's channel closes. */
    void end() {
        stop();
        for (NetConnection channel : new ArrayList<>(guests.values())) {
            channel.close();
        }
        guests.clear();
    }

    /** A message from the worker for a guest ({@code GUEST_SEND}, {@code GUEST_END}). */
    void fromWorker(int kind, byte[] bytes) {
        int number = WorldMessages.intAt(bytes, 0);
        NetConnection channel = guests.get(number);
        if (channel == null) {
            return;
        }
        if (kind == WorldMessages.GUEST_SEND) {
            channel.send(ByteBuffer.wrap(bytes, 4, bytes.length - 4));
        } else {
            guests.remove(number);
            channel.close();
        }
    }

    private static void say(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(message));
    }

    private final class Listener implements RelayHost.Listener {

        @Override
        public void shared(String code) {
            RelayHost current = host;
            String relay = current == null ? "" : current.relay();
            LOGGER.info("The world is shared on {} with the code {}", relay, code);
            MutableComponent codeText = Component.literal(code)
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.CopyToClipboard(code))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the code"))));
            say(Component.literal("Your world is shared. Join code: ")
                    .append(codeText)
                    .append(Component.literal(" on " + relay + ". Friends join from Multiplayer, Shared..."))
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public NetConnection.Listener guest(String clientId, NetConnection channel) {
            int number = ++nextGuest;
            guests.put(number, channel);
            byte[] id = WorldMessages.ints(number);
            worker.send(WorldMessages.GUEST_OPEN, id, 0, id.length);
            return new NetConnection.Listener() {
                @Override
                public void connected() {}

                @Override
                public void received(ByteBuffer data) {
                    byte[] message = new byte[4 + data.remaining()];
                    WorldMessages.putInt(message, 0, number);
                    data.get(message, 4, message.length - 4);
                    worker.send(WorldMessages.GUEST_MESSAGE, message, 0, message.length);
                }

                @Override
                public void closed(@Nullable Throwable cause) {
                    if (guests.remove(number) != null) {
                        worker.send(WorldMessages.GUEST_CLOSED, id, 0, id.length);
                    }
                }
            };
        }

        @Override
        public void trace(String step) {
            LOGGER.info("Sharing: {}", step);
        }

        @Override
        public void guestFailed(String clientId, String reason) {
            LOGGER.info("A guest couldn't connect ({}): {}", clientId, reason);
        }

        @Override
        public void stopped(String reason) {
            RelayHost current = host;
            host = null;
            if (stopped) {
                return;
            }
            if (current != null && current.code() == null) {
                LOGGER.warn("Couldn't share on {}: {}", current.relay(), reason);
                tryNextRelay();
                return;
            }
            say(Component.literal("The world is no longer shared: " + reason).withStyle(ChatFormatting.RED));
            stopped = true;
            worker.send(WorldMessages.SHARED, new byte[] {0}, 0, 1);
        }
    }
}
