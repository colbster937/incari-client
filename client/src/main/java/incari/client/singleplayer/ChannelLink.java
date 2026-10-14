package incari.client.singleplayer;

import incari.platform.api.net.NetConnection;
import incari.platform.api.threads.PlatformChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * One end of the game's connection between the page and singleplayer's server in a worker
 * (ADR 0039): a stream of the game's packets, each with its length in front as over TCP, sent a
 * flush at a time (ADR 0041). The server flushes its connections once a tick, so a tick's packets
 * cross as one channel message, where one message a packet woke the page's reader for each of
 * them. Whoever reads the channel hands this end what arrives ({@link #deliver}); the game's
 * network channel opens it through its address ({@link #address}) and hears it as a socket.
 */
public final class ChannelLink implements NetConnection {

    /** The batch buffer's size, and the size it goes back to after a larger batch. */
    private static final int OUTGOING_BYTES = 16 * 1024;

    /** Off ({@code ?packets=each}): each packet sent at once, a message each, to compare. */
    private static boolean batched = true;

    private final PlatformChannel channel;
    private final int sendKind;
    private final int closeKind;
    private volatile NetConnection.@Nullable Listener listener;
    private volatile boolean open = true;
    /** Packets that came before the game's channel took this end over, kept for it in order. */
    private final java.util.ArrayDeque<byte[]> early = new java.util.ArrayDeque<>();

    private boolean closedEarly;

    /** What this end sent since the last flush, sent then as one message. */
    private byte[] outgoing = new byte[OUTGOING_BYTES];

    private int outgoingLength;

    /**
     * @param sendKind the message kind this end's packets go out as
     * @param closeKind the message kind that tells the other end this one closed
     */
    ChannelLink(PlatformChannel channel, int sendKind, int closeKind) {
        this.channel = channel;
        this.sendKind = sendKind;
        this.closeKind = closeKind;
    }

    /** The address the game's network channel "connects" to, taking this end over. */
    InetSocketAddress address() {
        return new Address(this);
    }

    /**
     * A packet from the other end. One that comes before the game's channel has taken this end
     * over (the other end may send as soon as it's told this one exists) is kept until it has.
     */
    void deliver(byte[] packet) {
        NetConnection.Listener heard;
        synchronized (early) {
            heard = listener;
            if (heard == null) {
                early.add(packet);
                return;
            }
        }
        if (open) {
            heard.received(ByteBuffer.wrap(packet));
        }
    }

    /** The other end closed, or the channel ended. */
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

    /** The entry points' choice: false sends each packet as a message of its own, to compare (ADR 0041). */
    public static void setBatched(boolean on) {
        batched = on;
    }

    /** Adds to this flush's batch. */
    @Override
    public void send(ByteBuffer data) {
        if (!open) {
            return;
        }
        synchronized (this) {
            int length = data.remaining();
            if (outgoingLength + length > outgoing.length) {
                outgoing = Arrays.copyOf(outgoing, Math.max(outgoingLength + length, outgoing.length * 2));
            }
            data.get(outgoing, outgoingLength, length);
            outgoingLength += length;
        }
        if (!batched) {
            flush();
        }
    }

    /** Sends this flush's batch as one message. */
    @Override
    public void flush() {
        byte[] batch;
        synchronized (this) {
            if (outgoingLength == 0) {
                return;
            }
            batch = Arrays.copyOf(outgoing, outgoingLength);
            outgoingLength = 0;
            if (outgoing.length > OUTGOING_BYTES * 16) {
                // A big batch (a chunk's worth of packets) needn't keep its buffer.
                outgoing = new byte[OUTGOING_BYTES];
            }
        }
        if (open) {
            channel.send(sendKind, batch, 0, batch.length);
        }
    }

    @Override
    public void close() {
        if (open) {
            flush();
            open = false;
            channel.send(closeKind);
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
        return "singleplayer's server in a worker";
    }

    /**
     * The game's channel takes this end over: it hears that it's connected at once, then what came
     * before, then everything as it comes.
     */
    NetConnection open(NetConnection.Listener heard) {
        heard.connected();
        while (true) {
            byte[] packet;
            boolean closing;
            synchronized (early) {
                packet = early.poll();
                closing = closedEarly;
                if (packet == null) {
                    listener = heard;
                }
            }
            if (packet == null) {
                if (closing) {
                    closedByOtherEnd();
                }
                return this;
            }
            heard.received(ByteBuffer.wrap(packet));
        }
    }

    /** An address that opens a {@link ChannelLink}: 0.0.0.0 named "singleplayer", resolving nothing. */
    public static final class Address extends InetSocketAddress {

        private static final long serialVersionUID = 1L;

        private final transient ChannelLink link;

        Address(ChannelLink link) {
            super(placeholder(), 0);
            this.link = link;
        }

        /** The game's client channel takes this end over ({@code PlatformSocketChannel}). */
        public NetConnection open(NetConnection.Listener heard) {
            return link.open(heard);
        }

        private static InetAddress placeholder() {
            try {
                return InetAddress.getByAddress("singleplayer", new byte[4]);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String toString() {
            return "singleplayer";
        }
    }
}
