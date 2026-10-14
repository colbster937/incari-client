package incari.client.eagler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import incari.client.platform.ClientPlatform;
import incari.net.eagler.EaglerMessages;
import incari.platform.api.rtc.IceServer;
import incari.platform.api.rtc.Microphone;
import incari.platform.api.rtc.PlatformRtc;
import incari.platform.api.rtc.RtcPeer;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Eagler voice chat on one connection (ADR 0019). The server only signals: it tells each
 * client who else has voice on, pairs two clients up when one asks ({@code C_VOICE_REQUEST}), and
 * relays their WebRTC offers, answers and ICE candidates. The audio goes peer to peer, through the
 * platform's WebRTC ({@link PlatformRtc}), which only the browser has.
 *
 * <p>In the global channel every voice player is paired up; in proximity, those within range,
 * whose voices then fade with distance and pan with direction. Talking is push to talk.
 *
 * <p>Messages arrive on the connection's event loop and are handed to the game's thread; everything
 * else happens there.
 */
public final class EaglerVoice {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Proximity peers are asked for this much beyond the range, so they're ready before heard. */
    private static final double CONNECT_MARGIN = 6.0;
    /** A proximity peer out of range this long is let go. */
    private static final long LINGER_MILLIS = 5000;
    /** A level over this means a peer is talking. */
    private static final float TALKING_LEVEL = 0.06F;
    /** How long a peer still counts as talking after its last loud moment: speech has gaps. */
    private static final long TALKING_HOLD_MILLIS = 600;

    private static @Nullable EaglerVoice current;
    private static @Nullable VoiceSettings settings;

    private final PlatformRtc rtc;
    private final Map<UUID, Peer> peers = new HashMap<>();
    /** Everyone with voice on, from the server's list: names by UUID. */
    private final Map<UUID, String> voicePlayers = new LinkedHashMap<>();
    /** Peers asked for and not yet connected, so each is asked once. */
    private final Set<UUID> requested = new HashSet<>();
    /** When a proximity peer was last in range. */
    private final Map<UUID, Long> lastInRange = new HashMap<>();
    /** The players the player muted on this connection, whether paired with them now or not. */
    private final Set<UUID> muted = new HashSet<>();

    private @Nullable EaglerMessages messages;
    private @Nullable UUID self;
    private boolean capability;
    private boolean serverAllows;
    private List<IceServer> iceServers = List.of();
    /** The channel the server was told about: OFF until connected. */
    private VoiceSettings.Channel joined = VoiceSettings.Channel.OFF;

    private boolean transmitting;

    private EaglerVoice(PlatformRtc rtc) {
        this.rtc = rtc;
    }

    /** Whether this platform can do voice at all. */
    public static boolean supported() {
        return ClientPlatform.get().rtc().supported();
    }

    /** Voice for a new connection, or null where there's no WebRTC. */
    static @Nullable EaglerVoice start() {
        if (!supported()) {
            return null;
        }
        EaglerVoice voice = new EaglerVoice(ClientPlatform.get().rtc());
        Minecraft.getInstance().execute(() -> {
            EaglerVoice previous = current;
            if (previous != null) {
                previous.shutDown();
            }
            current = voice;
        });
        return voice;
    }

    /** The voice of the connection the player is on, if it has any. Game thread. */
    public static @Nullable EaglerVoice current() {
        return current;
    }

    /** The player's voice settings. Game thread. */
    public static VoiceSettings settings() {
        VoiceSettings loaded = settings;
        if (loaded == null) {
            loaded = VoiceSettings.load(Minecraft.getInstance().gameDirectory.toPath());
            settings = loaded;
        }
        return loaded;
    }

    // --- The connection's event loop ---------------------------------------------------------

    void loggedIn(UUID uuid, EaglerMessages sender, boolean accepted) {
        Minecraft.getInstance().execute(() -> {
            self = uuid;
            messages = sender;
            capability = accepted;
        });
    }

    void received(int id, ByteBuf body) {
        // Parsed here, where the buffer is valid; acted on, on the game's thread.
        Runnable action = parse(id, body);
        if (action != null) {
            Minecraft.getInstance().execute(action);
        }
    }

    void closed() {
        Minecraft.getInstance().execute(() -> {
            shutDown();
            if (current == this) {
                current = null;
            }
        });
    }

    private @Nullable Runnable parse(int id, ByteBuf body) {
        return switch (id) {
            case EaglerMessages.S_VOICE_ALLOWED -> {
                boolean allowed = body.readBoolean();
                List<IceServer> servers = new ArrayList<>();
                if (allowed) {
                    int count = EaglerMessages.readVarInt(body);
                    for (int i = 0; i < count && i < 64; i++) {
                        IceServer server = IceServer.parseEagler(EaglerMessages.readString(body));
                        if (server != null) {
                            servers.add(server);
                        }
                    }
                }
                yield () -> allowed(allowed, servers);
            }
            case EaglerMessages.S_VOICE_CONNECT -> {
                UUID peer = uuid(body);
                boolean offer = body.readBoolean();
                yield () -> connect(peer, offer);
            }
            case EaglerMessages.S_VOICE_CONNECT_ANNOUNCE -> {
                UUID peer = uuid(body);
                yield () -> announced(peer);
            }
            case EaglerMessages.S_VOICE_DISCONNECT_PEER -> {
                UUID peer = uuid(body);
                yield () -> close(peer, false);
            }
            case EaglerMessages.S_VOICE_ICE -> {
                UUID peer = uuid(body);
                String json = bytesText(body);
                yield () -> remoteCandidate(peer, json);
            }
            case EaglerMessages.S_VOICE_DESCRIPTION -> {
                UUID peer = uuid(body);
                String json = bytesText(body);
                yield () -> remoteDescription(peer, json);
            }
            case EaglerMessages.S_VOICE_GLOBAL -> {
                int count = EaglerMessages.readVarInt(body);
                List<UUID> uuids = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    uuids.add(uuid(body));
                }
                List<String> names = new ArrayList<>();
                if (body.isReadable()) {
                    for (int i = 0; i < count; i++) {
                        names.add(EaglerMessages.readString(body));
                    }
                }
                yield () -> global(uuids, names);
            }
            default -> null;
        };
    }

    // --- The game's thread -------------------------------------------------------------------

    /** Whether this server has voice and the player may use it. */
    public boolean available() {
        return capability && serverAllows;
    }

    /** The channel the player chose; takes effect on this server at once. */
    public void setChannel(VoiceSettings.Channel channel) {
        settings().setChannel(channel);
        apply();
    }

    public void setListenVolume(float volume) {
        settings().setListenVolume(volume);
    }

    public void setMicrophoneVolume(float volume) {
        settings().setMicrophoneVolume(volume);
        rtc.microphone().setVolume(volume);
    }

    public void setRange(int blocks) {
        settings().setRange(blocks);
    }

    /** The microphone's state, for the voice screen. */
    public Microphone.State microphone() {
        return rtc.microphone().state();
    }

    /** Whether the player is sending their voice now. */
    public boolean transmitting() {
        return transmitting;
    }

    /** Players connected by voice now: their names, and whether each is talking. */
    public List<Speaker> speakers() {
        List<Speaker> speakers = new ArrayList<>();
        for (Map.Entry<UUID, Peer> entry : peers.entrySet()) {
            Peer peer = entry.getValue();
            if (peer.audio) {
                speakers.add(new Speaker(
                        entry.getKey(), name(entry.getKey()), peer.talking, muted.contains(entry.getKey())));
            }
        }
        return speakers;
    }

    /** One voice: who, whether they're talking, whether the player muted them. */
    public record Speaker(UUID uuid, String name, boolean talking, boolean muted) {}

    /**
     * Mutes or unmutes one player's voice, for this connection: kept by UUID, so it holds when the
     * two are paired again (out of range and back, or a dropped peer).
     */
    public void setMuted(UUID player, boolean mute) {
        if (mute) {
            muted.add(player);
        } else {
            muted.remove(player);
        }
    }

    /** {@link incari.client.IncariTicks}: once a tick, on the game's thread. */
    public static void tick(Minecraft minecraft, boolean pushToTalk) {
        EaglerVoice voice = current;
        if (voice != null) {
            voice.update(minecraft, pushToTalk);
        }
    }

    private void allowed(boolean allowed, List<IceServer> servers) {
        serverAllows = allowed;
        iceServers = List.copyOf(servers);
        LOGGER.info("The server {} voice chat ({} ICE servers)", allowed ? "has" : "doesn't have", servers.size());
        apply();
    }

    /** Brings this server in line with the player's channel. */
    private void apply() {
        VoiceSettings.Channel wanted = available() ? settings().channel() : VoiceSettings.Channel.OFF;
        if (wanted == joined) {
            return;
        }
        if (joined != VoiceSettings.Channel.OFF) {
            closeAll(true);
            send(EaglerMessages.C_VOICE_DISCONNECT, body -> {});
            if (wanted == VoiceSettings.Channel.OFF) {
                // Voice off: the microphone goes too, and the browser stops showing it recording.
                releaseMicrophone();
            }
        }
        joined = wanted;
        LOGGER.info("Voice: {} channel", wanted.name().toLowerCase(java.util.Locale.ROOT));
        if (wanted != VoiceSettings.Channel.OFF) {
            Microphone microphone = rtc.microphone();
            microphone.open();
            microphone.setVolume(settings().microphoneVolume());
            send(EaglerMessages.C_VOICE_CONNECT, body -> {});
            if (wanted == VoiceSettings.Channel.GLOBAL) {
                voicePlayers.keySet().forEach(this::request);
            }
        }
    }

    private void global(List<UUID> uuids, List<String> names) {
        LOGGER.info("Voice: {} players have voice on", uuids.size());
        voicePlayers.clear();
        for (int i = 0; i < uuids.size(); i++) {
            voicePlayers.put(
                    uuids.get(i), i < names.size() ? names.get(i) : uuids.get(i).toString());
        }
        if (joined == VoiceSettings.Channel.GLOBAL) {
            uuids.forEach(this::request);
        }
    }

    /** {@code peer} turned voice on: asked for now in global chat, when in range in proximity. */
    private void announced(UUID peer) {
        requested.remove(peer);
        if (joined == VoiceSettings.Channel.GLOBAL) {
            request(peer);
        }
    }

    /** Asks the server to pair this client with {@code peer}. */
    private void request(UUID peer) {
        if (joined == VoiceSettings.Channel.OFF
                || peer.equals(self)
                || peers.containsKey(peer)
                || !requested.add(peer)) {
            return;
        }
        LOGGER.info("Voice: asking to pair with {}", name(peer));
        send(EaglerMessages.C_VOICE_REQUEST, body -> writeUuid(body, peer));
    }

    private void connect(UUID uuid, boolean offer) {
        if (joined == VoiceSettings.Channel.OFF) {
            return;
        }
        requested.remove(uuid);
        close(uuid, true);
        LOGGER.info("Voice: pairing with {}{}", name(uuid), offer ? ", offering" : "");
        Peer peer = new Peer(uuid);
        peer.connection = rtc.createPeer(iceServers, peer);
        peer.connection.sendMicrophone();
        peers.put(uuid, peer);
        if (offer) {
            peer.connection.createOffer();
        }
    }

    private void remoteDescription(UUID uuid, String json) {
        Peer peer = peers.get(uuid);
        if (peer != null && peer.connection != null) {
            peer.connection.setRemoteDescription(json);
        }
    }

    private void remoteCandidate(UUID uuid, String json) {
        Peer peer = peers.get(uuid);
        if (peer == null || peer.connection == null) {
            return;
        }
        try {
            JsonObject candidate = JsonParser.parseString(json).getAsJsonObject();
            // Eagler's clients send the line index as a string.
            int index = Integer.parseInt(candidate.get("sdpMLineIndex").getAsString());
            peer.connection.addIceCandidate(candidate.get("candidate").getAsString(), index);
        } catch (RuntimeException e) {
            LOGGER.warn("Unreadable ICE candidate from {}: {}", uuid, e.toString());
        }
    }

    /** Ends the connection to {@code uuid}; tells the server unless {@code quietly}. */
    private void close(UUID uuid, boolean quietly) {
        requested.remove(uuid);
        Peer peer = peers.remove(uuid);
        if (peer == null) {
            return;
        }
        if (peer.connection != null) {
            peer.connection.close();
        }
        if (!quietly) {
            send(EaglerMessages.C_VOICE_DISCONNECT_PEER, body -> writeUuid(body, uuid));
        }
    }

    private void closeAll(boolean quietly) {
        for (UUID uuid : new ArrayList<>(peers.keySet())) {
            close(uuid, quietly);
        }
        requested.clear();
        lastInRange.clear();
    }

    private void shutDown() {
        boolean wasOn = joined != VoiceSettings.Channel.OFF;
        closeAll(true);
        joined = VoiceSettings.Channel.OFF;
        // Only the voice that opened it lets it go: an old connection closing after the next one
        // turned voice on leaves the new one's microphone be.
        if (wasOn) {
            releaseMicrophone();
        }
    }

    /** Stops sending and closes the microphone (the platform's one, shared by every connection). */
    private void releaseMicrophone() {
        if (transmitting) {
            rtc.microphone().setTransmitting(false);
            transmitting = false;
        }
        rtc.microphone().close();
    }

    private void update(Minecraft minecraft, boolean pushToTalk) {
        if (joined == VoiceSettings.Channel.OFF) {
            return;
        }
        boolean talk = pushToTalk && rtc.microphone().state() == Microphone.State.OPEN;
        if (talk != transmitting) {
            transmitting = talk;
            rtc.microphone().setTransmitting(talk);
        }
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        VoiceSettings settings = settings();
        long now = System.currentTimeMillis();
        Vec3 ear = player.getEyePosition();
        Vec3 right = Vec3.directionFromRotation(0, player.getYRot() + 90);
        for (AbstractClientPlayer other : minecraft.level.players()) {
            if (other.getUUID().equals(player.getUUID())) {
                continue;
            }
            UUID uuid = other.getUUID();
            Vec3 toPeer = other.getEyePosition().subtract(ear);
            double distance = toPeer.length();
            Peer peer = peers.get(uuid);
            if (joined == VoiceSettings.Channel.PROXIMITY) {
                if (distance <= settings.range() + CONNECT_MARGIN) {
                    lastInRange.put(uuid, now);
                    request(uuid);
                }
                if (peer != null && peer.connection != null) {
                    float falloff = (float) Math.max(0, 1 - distance / settings.range());
                    float pan = distance < 0.5 ? 0 : (float) (toPeer.normalize().dot(right) * 0.8);
                    peer.connection.voice().setVolume(muted.contains(uuid) ? 0 : settings.listenVolume() * falloff);
                    peer.connection.voice().setPan(pan);
                }
            }
        }
        for (Map.Entry<UUID, Peer> entry : new ArrayList<>(peers.entrySet())) {
            Peer peer = entry.getValue();
            if (peer.connection == null) {
                continue;
            }
            if (joined == VoiceSettings.Channel.GLOBAL) {
                peer.connection.voice().setVolume(muted.contains(entry.getKey()) ? 0 : settings.listenVolume());
                peer.connection.voice().setPan(0);
            } else if (now - lastInRange.getOrDefault(entry.getKey(), 0L) > LINGER_MILLIS) {
                // Gone out of range (or out of sight): let go, and tell the server.
                close(entry.getKey(), false);
                continue;
            }
            if (!muted.contains(entry.getKey()) && peer.connection.voice().level() > TALKING_LEVEL) {
                peer.lastLoud = now;
            }
            boolean talking = now - peer.lastLoud < TALKING_HOLD_MILLIS;
            if (talking && !peer.talking) {
                // Once per stretch of talk: the browser checks watch for it.
                LOGGER.info("Voice: {} is talking", name(entry.getKey()));
            }
            peer.talking = talking;
        }
    }

    private String name(UUID uuid) {
        String name = voicePlayers.get(uuid);
        if (name != null) {
            return name;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            var info = minecraft.getConnection().getPlayerInfo(uuid);
            if (info != null) {
                return info.getProfile().name();
            }
        }
        return uuid.toString();
    }

    private void send(int id, java.util.function.Consumer<ByteBuf> body) {
        EaglerMessages sender = messages;
        if (sender != null) {
            sender.send(id, body);
        }
    }

    private static UUID uuid(ByteBuf body) {
        return new UUID(body.readLong(), body.readLong());
    }

    private static void writeUuid(ByteBuf body, UUID uuid) {
        body.writeLong(uuid.getMostSignificantBits());
        body.writeLong(uuid.getLeastSignificantBits());
    }

    private static String bytesText(ByteBuf body) {
        int length = EaglerMessages.readVarInt(body);
        String text = body.toString(body.readerIndex(), length, StandardCharsets.UTF_8);
        body.skipBytes(length);
        return text;
    }

    private static void writeBytesText(ByteBuf body, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        EaglerMessages.writeVarInt(body, bytes.length);
        body.writeBytes(bytes);
    }

    /** One voice connection, and what its WebRTC peer says. Game thread. */
    private final class Peer implements RtcPeer.Listener {

        final UUID uuid;

        @Nullable
        RtcPeer connection;

        boolean audio;
        boolean talking;
        /** When the peer was last louder than {@link #TALKING_LEVEL}. */
        long lastLoud = Long.MIN_VALUE / 2;

        Peer(UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public void localDescription(String json) {
            send(EaglerMessages.C_VOICE_DESCRIPTION, body -> {
                writeUuid(body, uuid);
                writeBytesText(body, json);
            });
        }

        @Override
        public void iceCandidate(String candidate, int sdpMLineIndex) {
            JsonObject json = new JsonObject();
            // As Eagler's clients write it: the index as a string.
            json.addProperty("sdpMLineIndex", Integer.toString(sdpMLineIndex));
            json.addProperty("candidate", candidate);
            String text = json.toString();
            send(EaglerMessages.C_VOICE_ICE, body -> {
                writeUuid(body, uuid);
                writeBytesText(body, text);
            });
        }

        @Override
        public void remoteAudio() {
            audio = true;
            LOGGER.info("Voice: connected to {}", name(uuid));
        }

        @Override
        public void stateChanged(String state) {
            if (("failed".equals(state) || "disconnected".equals(state)) && peers.get(uuid) == this) {
                LOGGER.info("Voice connection to {} {}", name(uuid), state);
                close(uuid, false);
            }
        }
    }
}
