package incari.net.relay;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import incari.platform.api.rtc.IceServer;
import incari.platform.api.rtc.PlatformRtc;
import incari.platform.api.rtc.RtcPeer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Shares a world through an Eagler relay (ADR 0062): the host's side of {@link RelayJoin}, as
 * Eagler's own hosts do it.
 *
 * <ol>
 *   <li>The host says hello with the world's name; the relay answers with a join code.
 *   <li>The relay announces each client that uses the code. The host answers its offer, sends ICE
 *       candidates once it has the client's, and takes the "lan" data channel the client opens.
 *   <li>On that channel the host sends {@link HostHello}, then hands the channel to the world
 *       ({@link Listener#guest}).
 * </ol>
 *
 * <p>The relay's WebSocket stays open while the world is shared, so more can join. Game thread;
 * {@link #tick} (once a frame) runs the timers.
 */
public final class RelayHost implements NetConnection.Listener {

    /** Hears how sharing goes. Game thread. */
    public interface Listener {

        /** The relay gave the world {@code code}: what guests type to join. */
        void shared(String code);

        /**
         * A guest's data channel opened (after the host's hello). Returns who hears it from now on:
         * the world's connection for the guest.
         */
        NetConnection.Listener guest(String clientId, NetConnection channel);

        /** A guest couldn't connect; for the host's log. */
        default void guestFailed(String clientId, String reason) {}

        /** A step of sharing, for the host's log. */
        default void trace(String step) {}

        /** Sharing stopped (the relay closed, or refused); the reason is for the player. */
        void stopped(String reason);
    }

    /** How long the relay may take to give a code, and a client to finish connecting. */
    private static final long STEP_MILLIS = 15_000;
    /** How long after the client's candidates this side sends its own, if gathering isn't done. */
    private static final long GATHER_MILLIS = 2_000;
    /** At most this many guests connecting at once: a relay is public, and each costs a peer. */
    private static final int MAX_CONNECTING = 8;

    private final PlatformNetwork network;
    private final PlatformRtc rtc;
    private final String relay;
    private final String worldName;
    private final int protocol;
    private final Listener listener;
    private final LongSupplier clock;
    private final Map<String, Guest> connecting = new LinkedHashMap<>();

    private @Nullable NetConnection socket;
    private List<IceServer> iceServers = List.of();
    private @Nullable String code;
    private long deadline;
    private boolean stopped;

    /**
     * @param protocol the game's protocol number, for the hello Incari guests read
     */
    public RelayHost(
            PlatformNetwork network,
            PlatformRtc rtc,
            String relay,
            String worldName,
            int protocol,
            Listener listener,
            LongSupplier clock) {
        this.network = network;
        this.rtc = rtc;
        this.relay = relay;
        this.worldName = worldName;
        this.protocol = protocol;
        this.listener = listener;
        this.clock = clock;
    }

    /** Opens the relay's WebSocket. */
    public void start() {
        deadline = clock.getAsLong() + STEP_MILLIS;
        socket = network.openWebSocket(relay, this);
    }

    /** The join code, once the relay has given one. */
    public @Nullable String code() {
        return code;
    }

    /** The relay's address. */
    public String relay() {
        return relay;
    }

    /** Runs the deadlines; call once a frame. */
    public void tick() {
        if (stopped) {
            return;
        }
        long now = clock.getAsLong();
        if (code == null && now > deadline) {
            stop("The relay didn't give the world a code");
            return;
        }
        for (Guest guest : new ArrayList<>(connecting.values())) {
            guest.tick(now);
        }
    }

    /** Stops sharing: no one new can join. Guests already in stay, on their own channels. */
    public void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        for (Guest guest : new ArrayList<>(connecting.values())) {
            guest.fail("The host stopped sharing");
        }
        closeSocket();
    }

    // --- The relay's WebSocket ---------------------------------------------------------------

    @Override
    public void connected() {
        listener.trace("connected to " + relay + "; saying hello");
        send(RelayPackets.hostHandshake(worldName, false));
    }

    @Override
    public void received(ByteBuffer data) {
        if (stopped) {
            return;
        }
        ByteBuf in = Unpooled.wrappedBuffer(data);
        RelayPackets.Message message;
        try {
            message = RelayPackets.read(in);
        } catch (IndexOutOfBoundsException | DecoderException e) {
            stop("The relay sent something unreadable");
            return;
        }
        listener.trace("heard " + message.getClass().getSimpleName());
        // An instanceof chain, not a pattern switch: the browser build's compiler (TeaVM) can't
        // link the switch's type bootstrap.
        if (message instanceof RelayPackets.Handshake hello) {
            if (code == null) {
                code = hello.code();
                listener.shared(hello.code());
            }
        } else if (message instanceof RelayPackets.IceServers servers) {
            iceServers = servers.servers();
        } else if (message instanceof RelayPackets.NewClient client) {
            newGuest(client.clientId());
        } else if (message instanceof RelayPackets.Signal signal) {
            Guest guest = connecting.get(signal.peerId());
            if (guest != null) {
                guest.signal(signal);
            }
        } else if (message instanceof RelayPackets.ClientDone done) {
            Guest guest = connecting.get(done.clientId());
            if (guest != null && !done.success()) {
                guest.fail("The guest's side failed");
            }
        } else if (message instanceof RelayPackets.Refusal refusal) {
            stop(
                    refusal.code() == 0x01
                            ? "The relay runs a different version (" + refusal.message() + ")"
                            : "The relay refused: " + refusal.message());
        }
    }

    @Override
    public void closed(@Nullable Throwable cause) {
        listener.trace("the relay's connection closed" + (cause == null ? "" : ": " + cause.getMessage()));
        if (!stopped) {
            stop(cause == null ? "The relay closed the connection" : "Couldn't reach the relay: " + cause.getMessage());
        }
    }

    private void newGuest(String clientId) {
        if (connecting.size() >= MAX_CONNECTING || connecting.containsKey(clientId)) {
            return;
        }
        Guest guest = new Guest(clientId);
        connecting.put(clientId, guest);
        guest.peer = rtc.createPeer(iceServers, guest);
        guest.deadline = clock.getAsLong() + STEP_MILLIS;
    }

    private void stop(String reason) {
        if (stopped) {
            return;
        }
        listener.trace("stopping: " + reason);
        close();
        listener.stopped(reason);
    }

    private void send(ByteBuffer message) {
        NetConnection current = socket;
        if (current != null) {
            current.send(message);
        }
    }

    private void closeSocket() {
        NetConnection current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
    }

    // --- One guest connecting ----------------------------------------------------------------

    private final class Guest implements RtcPeer.Listener {

        private final String id;
        private final List<IceJson.Candidate> candidates = new ArrayList<>();
        private @Nullable RtcPeer peer;
        private long deadline;
        private boolean gathered;
        /** When the client's candidates came; 0 before. */
        private long theirsAt;

        private boolean sentOurs;

        Guest(String id) {
            this.id = id;
        }

        void tick(long now) {
            if (theirsAt > 0 && !sentOurs && (gathered || now - theirsAt > GATHER_MILLIS)) {
                sendCandidates();
            }
            if (now > deadline) {
                fail("Timed out connecting");
            }
        }

        void signal(RelayPackets.Signal signal) {
            RtcPeer current = peer;
            if (current == null) {
                return;
            }
            try {
                if (signal.id() == RelayPackets.DESCRIPTION) {
                    current.setRemoteDescription(signal.text());
                } else if (signal.id() == RelayPackets.ICE_CANDIDATE) {
                    for (IceJson.Candidate candidate : IceJson.read(signal.text())) {
                        current.addIceCandidate(candidate.candidate(), candidate.sdpMLineIndex());
                    }
                    theirsAt = clock.getAsLong();
                }
            } catch (RuntimeException e) {
                // A guest's text that can't be read fails that guest only, never the world's
                // sharing (or the frame it arrived in: this runs in the platform's event poll).
                fail("The guest sent something unreadable");
            }
        }

        @Override
        public void localDescription(String json) {
            send(RelayPackets.description(id, json));
        }

        @Override
        public void iceCandidate(String candidate, int sdpMLineIndex) {
            candidates.add(new IceJson.Candidate(candidate, sdpMLineIndex));
        }

        @Override
        public void iceGatheringComplete() {
            gathered = true;
        }

        @Override
        public void stateChanged(String state) {
            if ("failed".equals(state) && connecting.containsKey(id)) {
                fail("Couldn't connect the host and the guest");
            }
        }

        @Override
        public void dataChannel(String label, RtcPeer.IncomingChannel incoming) {
            if (!label.equals(RelayJoin.CHANNEL) || !connecting.containsKey(id)) {
                return;
            }
            Forward forward = new Forward();
            NetConnection channel = incoming.accept(forward);
            forward.channel = channel;
            forward.guestId = id;
            forward.peer = peer;
            if (channel.isOpen()) {
                forward.connected();
            }
        }

        private void sendCandidates() {
            sentOurs = true;
            send(RelayPackets.iceCandidates(id, IceJson.write(candidates)));
        }

        /** The channel opened: the guest is the world's now, and this side leaves it. */
        void joined() {
            connecting.remove(id);
        }

        void fail(String reason) {
            if (connecting.remove(id) == null) {
                return;
            }
            RtcPeer current = peer;
            if (current != null) {
                current.close();
            }
            listener.guestFailed(id, reason);
        }
    }

    /**
     * A guest's data channel: says the host's hello when it opens, then hands everything to the
     * world's connection for the guest.
     */
    private final class Forward implements NetConnection.Listener {

        private @Nullable NetConnection channel;
        /** The guest's peer connection, closed with the channel: it carries nothing else. */
        private @Nullable RtcPeer peer;

        private String guestId = "";
        private NetConnection.@Nullable Listener target;
        private boolean opened;

        @Override
        public void connected() {
            NetConnection current = channel;
            if (opened || current == null) {
                return;
            }
            opened = true;
            current.send(HostHello.write(protocol));
            Guest guest = connecting.get(guestId);
            if (guest != null) {
                guest.joined();
            }
            NetConnection.Listener world = listener.guest(guestId, current);
            target = world;
            world.connected();
        }

        @Override
        public void received(ByteBuffer data) {
            NetConnection.Listener world = target;
            if (world != null) {
                world.received(data);
            }
        }

        @Override
        public void closed(@Nullable Throwable cause) {
            NetConnection.Listener world = target;
            if (world != null) {
                RtcPeer current = peer;
                peer = null;
                if (current != null) {
                    current.close();
                }
                world.closed(cause);
            } else {
                Guest guest = connecting.get(guestId);
                if (guest != null) {
                    guest.fail("The guest's channel closed");
                }
            }
        }
    }
}
