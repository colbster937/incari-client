package incari.net.relay;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import incari.platform.api.rtc.PlatformRtc;
import incari.platform.api.rtc.RtcPeer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Joining a shared world through an Eagler relay (ADR 0019), up to an open data channel to
 * its host. The relay is a WebSocket server that knows each shared world by a code and passes
 * WebRTC signalling between the world's host and whoever joins:
 *
 * <ol>
 *   <li>the client says hello with the code; the relay answers hello, then its STUN and TURN
 *       servers;
 *   <li>the client makes a peer connection with a data channel ("lan") and sends its offer; the
 *       host answers;
 *   <li>the client sends all its ICE candidates at once, when it has them; the host sends its own;
 *   <li>once the data channel opens, the client tells the relay it succeeded and leaves;
 *   <li>an Incari host then says so first on the channel ({@link HostHello}, ADR 0062); a moment
 *       without it means one of Eagler's.
 * </ol>
 *
 * Every step has a deadline. The platform's calls come on the game's thread; {@link #tick} (once a
 * frame) runs the timers.
 */
public final class RelayJoin implements NetConnection.Listener, RtcPeer.Listener {

    /** Hears how a join ends. Game thread. */
    public interface Listener {

        /** What the join is doing, for the player. */
        default void progress(String status) {}

        /** The data channel is open; the game connects to {@code address}. */
        void joined(RelayWorldAddress address);

        /** The join failed; the reason is for the player. */
        void failed(String reason);
    }

    /** How long each step may take. */
    private static final long STEP_MILLIS = 10_000;
    /** How long after its last new ICE candidate this side is taken to have them all. */
    private static final long GATHER_QUIET_MILLIS = 2_000;
    /** The data channel's label, as the hosts expect. */
    static final String CHANNEL = "lan";

    private enum State {
        CONNECTING,
        HELLO,
        ICE_SERVERS,
        OFFERING,
        AWAITING_ANSWER,
        GATHERING,
        AWAITING_HOST_CANDIDATES,
        OPENING,
        /** The channel is open: a moment for an Incari host's hello (ADR 0062). */
        HEARING_HOST,
        JOINED,
        FAILED
    }

    private final PlatformNetwork network;
    private final PlatformRtc rtc;
    private final String relay;
    private final String code;
    private final Listener listener;
    private final LongSupplier clock;
    private final List<IceJson.Candidate> candidates = new ArrayList<>();

    private State state = State.CONNECTING;
    private long deadline;
    private long lastCandidate;
    private boolean gatheringComplete;
    private String hostPeer = "";
    private @Nullable NetConnection socket;
    private @Nullable RtcPeer peer;
    private @Nullable NetConnection channel;
    private final RelayWorldAddress.Buffered buffered = new RelayWorldAddress.Buffered(this::channelOpened);

    public RelayJoin(
            PlatformNetwork network,
            PlatformRtc rtc,
            String relay,
            String code,
            Listener listener,
            LongSupplier clock) {
        this.network = network;
        this.rtc = rtc;
        this.relay = relay;
        this.code = code;
        this.listener = listener;
        this.clock = clock;
    }

    /** Opens the relay's WebSocket. */
    public void start() {
        deadline = clock.getAsLong() + STEP_MILLIS;
        listener.progress("Connecting to " + relay);
        socket = network.openWebSocket(relay, this);
    }

    /** Runs the deadlines; call once a frame. */
    public void tick() {
        if (state == State.JOINED || state == State.FAILED) {
            return;
        }
        long now = clock.getAsLong();
        if (state == State.HEARING_HOST) {
            if (buffered.heard || now > deadline) {
                join();
            }
            return;
        }
        if (state == State.GATHERING
                && (gatheringComplete || (!candidates.isEmpty() && now - lastCandidate > GATHER_QUIET_MILLIS))) {
            sendCandidates();
            return;
        }
        if (now > deadline) {
            fail(
                    switch (state) {
                        case CONNECTING, HELLO -> "The relay didn't answer";
                        case ICE_SERVERS -> "The relay didn't send connection details";
                        case AWAITING_ANSWER -> "The world's host didn't answer (is the code right?)";
                        case GATHERING -> "Couldn't find a way to connect to the host";
                        case AWAITING_HOST_CANDIDATES -> "The host couldn't find a way to connect";
                        default -> "Couldn't reach the world's host";
                    });
        }
    }

    /** Gives up, closing whatever is open. */
    public void cancel() {
        fail("Cancelled");
    }

    // --- The relay's WebSocket ---------------------------------------------------------------

    @Override
    public void connected() {
        if (state != State.CONNECTING) {
            return;
        }
        state = State.HELLO;
        send(RelayPackets.handshake(code));
    }

    @Override
    public void received(ByteBuffer data) {
        if (state == State.JOINED || state == State.FAILED) {
            return;
        }
        ByteBuf in = Unpooled.wrappedBuffer(data);
        RelayPackets.Message message;
        try {
            message = RelayPackets.read(in);
        } catch (IndexOutOfBoundsException | DecoderException e) {
            fail("The relay sent something unreadable");
            return;
        }
        if (message instanceof RelayPackets.Refusal error) {
            fail(errorText(error));
        } else if (message instanceof RelayPackets.Handshake && state == State.HELLO) {
            state = State.ICE_SERVERS;
            deadline = clock.getAsLong() + STEP_MILLIS;
            listener.progress("Finding the world " + code);
        } else if (message instanceof RelayPackets.IceServers servers && state == State.ICE_SERVERS) {
            RtcPeer created = rtc.createPeer(servers.servers(), this);
            peer = created;
            channel = created.openDataChannel(CHANNEL, buffered);
            state = State.OFFERING;
            deadline = clock.getAsLong() + STEP_MILLIS;
            created.createOffer();
            listener.progress("Asking the world's host to connect");
        } else if (message instanceof RelayPackets.Signal signal) {
            signal(signal);
        } else if (!(message instanceof RelayPackets.Ignored)) {
            fail("The relay sent a message out of turn");
        }
    }

    @Override
    public void closed(@Nullable Throwable cause) {
        if (state != State.JOINED && state != State.FAILED && state != State.OPENING && state != State.HEARING_HOST) {
            fail(cause == null ? "The relay closed the connection" : "Couldn't reach the relay: " + cause.getMessage());
        }
    }

    private void signal(RelayPackets.Signal signal) {
        RtcPeer current = peer;
        if (current == null) {
            fail("The relay sent a message out of turn");
            return;
        }
        if (signal.id() == RelayPackets.DESCRIPTION && state == State.AWAITING_ANSWER) {
            current.setRemoteDescription(signal.text());
            state = State.GATHERING;
            deadline = clock.getAsLong() + STEP_MILLIS;
        } else if (signal.id() == RelayPackets.ICE_CANDIDATE && state == State.AWAITING_HOST_CANDIDATES) {
            hostPeer = signal.peerId();
            try {
                for (IceJson.Candidate candidate : IceJson.read(signal.text())) {
                    current.addIceCandidate(candidate.candidate(), candidate.sdpMLineIndex());
                }
            } catch (RuntimeException e) {
                // Runs in the platform's event poll: a host's unreadable text fails the join only.
                fail("The world's host sent something unreadable");
                return;
            }
            state = State.OPENING;
            deadline = clock.getAsLong() + STEP_MILLIS;
            listener.progress("Opening the connection");
            if (buffered.isOpen()) {
                channelOpened();
            }
        } else {
            fail("The relay sent a message out of turn");
        }
    }

    // --- The peer connection -----------------------------------------------------------------

    @Override
    public void localDescription(String json) {
        if (state == State.OFFERING) {
            send(RelayPackets.description(json));
            state = State.AWAITING_ANSWER;
            deadline = clock.getAsLong() + STEP_MILLIS;
        }
    }

    @Override
    public void iceCandidate(String candidate, int sdpMLineIndex) {
        candidates.add(new IceJson.Candidate(candidate, sdpMLineIndex));
        lastCandidate = clock.getAsLong();
    }

    @Override
    public void iceGatheringComplete() {
        gatheringComplete = true;
    }

    @Override
    public void stateChanged(String connectionState) {
        if ("failed".equals(connectionState) && state != State.JOINED) {
            fail("Couldn't connect to the world's host (the network between you may block it)");
        }
    }

    private void sendCandidates() {
        send(RelayPackets.iceCandidates(IceJson.write(candidates)));
        state = State.AWAITING_HOST_CANDIDATES;
        deadline = clock.getAsLong() + STEP_MILLIS;
    }

    private void channelOpened() {
        if (state != State.OPENING) {
            // Opened before the host's candidates arrived (a direct route): finished when they do.
            return;
        }
        if (channel == null) {
            return;
        }
        // An Incari host speaks first (HostHello); Eagler's never do, so the wait ends at the
        // first message or after a moment.
        state = State.HEARING_HOST;
        deadline = clock.getAsLong() + HostHello.WAIT_MILLIS;
        send(RelayPackets.clientSuccess(hostPeer));
        closeSocket();
        if (buffered.heard) {
            join();
        }
    }

    private void join() {
        NetConnection opened = channel;
        if (opened == null || state != State.HEARING_HOST) {
            return;
        }
        state = State.JOINED;
        listener.joined(new RelayWorldAddress(code, opened, buffered));
    }

    // --- Endings -----------------------------------------------------------------------------

    private void fail(String reason) {
        if (state == State.JOINED || state == State.FAILED) {
            return;
        }
        if (state == State.HEARING_HOST) {
            // The channel opened and the relay was told: what's left is the channel.
            state = State.FAILED;
            NetConnection opened = channel;
            if (opened != null) {
                opened.close();
            }
            RtcPeer current = peer;
            if (current != null) {
                current.close();
            }
            listener.failed(reason);
            return;
        }
        boolean told = state == State.OPENING;
        state = State.FAILED;
        if (told) {
            send(RelayPackets.clientFailure(hostPeer));
        }
        RtcPeer current = peer;
        if (current != null) {
            current.close();
        }
        closeSocket();
        listener.failed(reason);
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

    private String errorText(RelayPackets.Refusal error) {
        return switch (error.code()) {
            case 0x01 -> "The relay runs a different version (" + error.message() + ")";
            case 0x04, 0x05 -> "No world is shared with the code " + code;
            case 0x06 -> "The world's host left";
            default -> "The relay refused: " + error.message();
        };
    }
}
