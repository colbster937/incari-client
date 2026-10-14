package incari.client.singleplayer;

import incari.platform.api.net.NetConnection;
import incari.platform.api.threads.PlatformChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.jspecify.annotations.Nullable;

/**
 * The worker's end of a shared world's guest (ADR 0062): the guest's WebRTC data channel lives in
 * the page, which hands each of its messages here and sends what this end sends. Messages keep their
 * edges (one data channel message each, as {@code LanFraming} and the message framing above it
 * expect), so nothing is batched: a guest's packets are few next to the owner's.
 *
 * <p>Every message carries the guest's number first (four bytes), as {@link WorldMessages} says.
 */
public final class GuestLink implements NetConnection {

    private final PlatformChannel page;
    private final int guest;
    private volatile NetConnection.@Nullable Listener listener;
    private volatile boolean open = true;
    private final ArrayDeque<byte[]> early = new ArrayDeque<>();
    private boolean closedEarly;

    GuestLink(PlatformChannel page, int guest) {
        this.page = page;
        this.guest = guest;
    }

    /** The address the server's channel "connects" to, taking this end over. */
    InetSocketAddress address() {
        return new Address(this);
    }

    /** A message from the guest, kept until the server's channel has taken this end over. */
    void deliver(byte[] message) {
        NetConnection.Listener heard;
        synchronized (early) {
            heard = listener;
            if (heard == null) {
                early.add(message);
                return;
            }
        }
        if (open) {
            heard.received(ByteBuffer.wrap(message));
        }
    }

    /** The guest's channel closed in the page. */
    void closedByOtherEnd() {
        NetConnection.Listener heard;
        synchronized (early) {
            heard = listener;
            if (heard == null) {
                closedEarly = true;
                return;
            }
        }
        if (open) {
            open = false;
            heard.closed(null);
        }
    }

    @Override
    public void send(ByteBuffer data) {
        if (!open) {
            return;
        }
        byte[] message = new byte[4 + data.remaining()];
        WorldMessages.putInt(message, 0, guest);
        data.get(message, 4, message.length - 4);
        page.send(WorldMessages.GUEST_SEND, message, 0, message.length);
    }

    @Override
    public void close() {
        if (open) {
            open = false;
            byte[] id = WorldMessages.ints(guest);
            page.send(WorldMessages.GUEST_END, id, 0, id.length);
            NetConnection.Listener heard = listener;
            if (heard != null) {
                heard.closed(null);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public String remoteDescription() {
        return "a shared world's guest";
    }

    NetConnection open(NetConnection.Listener heard) {
        heard.connected();
        while (true) {
            byte[] message;
            boolean closing;
            synchronized (early) {
                message = early.poll();
                closing = closedEarly;
                if (message == null) {
                    listener = heard;
                }
            }
            if (message == null) {
                if (closing) {
                    closedByOtherEnd();
                }
                return this;
            }
            heard.received(ByteBuffer.wrap(message));
        }
    }

    /** An address that opens a {@link GuestLink}: 0.0.0.0 named "shared-world-guest". */
    public static final class Address extends InetSocketAddress {

        private static final long serialVersionUID = 1L;

        private final transient GuestLink link;

        Address(GuestLink link) {
            super(placeholder(), 0);
            this.link = link;
        }

        /** The server's channel takes this end over ({@code PlatformSocketChannel}). */
        public NetConnection open(NetConnection.Listener heard) {
            return link.open(heard);
        }

        private static InetAddress placeholder() {
            try {
                return InetAddress.getByAddress("shared-world-guest", new byte[4]);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
