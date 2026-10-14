package incari.net.relay;

import incari.platform.api.net.NetConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A shared world's address once {@link RelayJoin} has opened a data channel to its host: the
 * game's connection "connects" to it by taking over that channel. Messages that arrive before
 * then are kept for it.
 */
public final class RelayWorldAddress extends InetSocketAddress {

    private static final long serialVersionUID = 1L;

    private final transient Buffered buffered;
    private final String code;

    RelayWorldAddress(String code, NetConnection channel, Buffered buffered) {
        super(placeholder(), 0);
        this.code = code;
        this.buffered = buffered;
        buffered.channel = channel;
    }

    /** 0.0.0.0 named "shared-world": resolves nothing, as for WebSocketAddress. */
    private static InetAddress placeholder() {
        try {
            return InetAddress.getByAddress("shared-world", new byte[4]);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The world's join code. */
    public String code() {
        return code;
    }

    /**
     * The game's protocol the host plays if it's an Incari host ({@link HostHello}, ADR 0062): the
     * guest joins it as it is. -1 for Eagler's hosts, which play 1.8 with Eagler's login.
     */
    public int incariProtocol() {
        return buffered.incariProtocol;
    }

    /**
     * The data channel, now {@code listener}'s: it hears that the channel is open at once, then
     * whatever came before, then everything as it comes.
     */
    public NetConnection open(NetConnection.Listener listener) {
        return buffered.attach(listener);
    }

    @Override
    public String toString() {
        return "shared world " + code;
    }

    /** Hears the data channel from its creation, until the game's connection takes it over. */
    static final class Buffered implements NetConnection.Listener {

        private final List<ByteBuffer> early = new ArrayList<>();
        /** The protocol an Incari host's hello named; -1 before one, or for Eagler's hosts. */
        int incariProtocol = -1;

        /** Whether anything came on the channel yet: a hello comes first or not at all. */
        boolean heard;

        private @Nullable NetConnection channel;
        private NetConnection.@Nullable Listener target;
        private boolean open;
        private boolean closed;
        private @Nullable Throwable closeCause;

        /** The join, told when the channel opens or closes before the game takes it. */
        private final Runnable onOpen;

        Buffered(Runnable onOpen) {
            this.onOpen = onOpen;
        }

        boolean isOpen() {
            return open && !closed;
        }

        NetConnection attach(NetConnection.Listener listener) {
            NetConnection current = channel;
            if (current == null) {
                throw new IllegalStateException("No data channel yet");
            }
            target = listener;
            if (closed) {
                listener.closed(closeCause);
                return current;
            }
            listener.connected();
            for (ByteBuffer message : early) {
                listener.received(message);
            }
            early.clear();
            return current;
        }

        @Override
        public void connected() {
            open = true;
            NetConnection.Listener listener = target;
            if (listener == null) {
                onOpen.run();
            }
        }

        @Override
        public void received(ByteBuffer data) {
            if (!heard) {
                heard = true;
                int protocol = HostHello.read(data);
                if (protocol >= 0) {
                    // The host's hello is for the join, not the game's connection.
                    incariProtocol = protocol;
                    return;
                }
            }
            NetConnection.Listener listener = target;
            if (listener != null) {
                listener.received(data);
            } else {
                early.add(data);
            }
        }

        @Override
        public void closed(@Nullable Throwable cause) {
            closed = true;
            closeCause = cause;
            NetConnection.Listener listener = target;
            if (listener != null) {
                listener.closed(cause);
            }
        }
    }
}
