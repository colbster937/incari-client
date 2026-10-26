package incari.net.wisp;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A TCP connection to a Java server through a Wisp proxy (protocol v1): one WebSocket to the proxy,
 * one Wisp stream on it to the server, and the bytes of the stream passed up as a TCP connection's
 * would be. First, for a host given without a port, a second stream asks a resolver for its SRV
 * record ({@link SrvLookup}).
 *
 * <p>Every Wisp packet is one WebSocket message: a type byte, a stream id and its payload, little
 * endian. The proxy grants a number of DATA packets per stream (CONTINUE) and grants more as it
 * forwards them; what's sent beyond that waits. What the game sends between flushes goes as one
 * DATA packet, so a tick's packets cost one message, not one each.
 *
 * <p>No Sec-WebSocket-Protocol header is sent, so a v2 proxy speaks v1 to it.
 */
public final class WispConnection implements NetConnection {

    private static final byte CONNECT = 0x01;
    private static final byte DATA = 0x02;
    private static final byte CONTINUE = 0x03;
    private static final byte CLOSE = 0x04;
    private static final byte STREAM_TCP = 0x01;
    private static final byte CLOSE_VOLUNTARY = 0x02;
    private static final int HEADER = 5;

    /** Stream ids are the client's choice; each connection has its own WebSocket, so fixed ones do. */
    private static final int GAME_STREAM = 1;

    private static final int SRV_STREAM = 2;
    private static final int SRV_QUERY_ID = 0x1CA7;

    private final WispAddress address;
    private final Listener listener;
    private @Nullable NetConnection socket;

    // Guarded by this.
    /** DATA packets each new stream may send before a CONTINUE: the proxy's first packet. -1 before. */
    private int initialCredit = -1;
    /** DATA packets the game stream may still send. */
    private int credit;

    private boolean gameOpen;
    private boolean closed;
    private final List<ByteBuffer> unsent = new ArrayList<>();
    private int unsentBytes;
    /** The SRV reply so far, while the lookup runs; null otherwise. */
    private byte @Nullable [] srvReply;

    private int srvReplyCount;

    private WispConnection(WispAddress address, Listener listener) {
        this.address = address;
        this.listener = listener;
    }

    /** Opens a WebSocket to {@code address}'s proxy, and through it a stream to the server. Returns at once. */
    public static NetConnection open(PlatformNetwork network, WispAddress address, Listener listener) {
        WispConnection connection = new WispConnection(address, listener);
        synchronized (connection) {
            // Held so the socket's first callbacks wait for the field.
            connection.socket = network.openWebSocket(address.proxy().toString(), connection.new SocketListener());
        }
        return connection;
    }

    @Override
    public void send(ByteBuffer data) {
        synchronized (this) {
            if (closed) {
                return;
            }
            unsent.add(data);
            unsentBytes += data.remaining();
        }
    }

    @Override
    public void flush() {
        synchronized (this) {
            sendUnsent();
        }
    }

    @Override
    public void close() {
        boolean tell;
        synchronized (this) {
            tell = !closed;
            if (tell && gameOpen) {
                sendPacket(CLOSE, GAME_STREAM, new byte[] {CLOSE_VOLUNTARY});
            }
            finish();
        }
        if (tell) {
            listener.closed(null);
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return gameOpen && !closed;
    }

    @Override
    public String remoteDescription() {
        return address.toString();
    }

    // --- Under the lock ------------------------------------------------------------------------

    /** Sends what the game queued as one DATA packet, if the proxy grants one. */
    private void sendUnsent() {
        if (!gameOpen || closed || unsent.isEmpty() || credit <= 0) {
            return;
        }
        ByteBuffer packet = header(DATA, GAME_STREAM, unsentBytes);
        for (ByteBuffer data : unsent) {
            packet.put(data);
        }
        unsent.clear();
        unsentBytes = 0;
        credit--;
        write(packet);
    }

    private void sendPacket(byte type, int stream, byte[] payload) {
        ByteBuffer packet = header(type, stream, payload.length);
        packet.put(payload);
        write(packet);
    }

    private void write(ByteBuffer packet) {
        NetConnection current = socket;
        if (current != null) {
            packet.flip();
            current.send(packet);
        }
    }

    private static ByteBuffer header(byte type, int stream, int payloadLength) {
        ByteBuffer packet = ByteBuffer.allocate(HEADER + payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        return packet.put(type).putInt(stream);
    }

    private void connect(int stream, String host, int port) {
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = header(CONNECT, stream, 3 + name.length);
        packet.put(STREAM_TCP).putShort((short) port).put(name);
        write(packet);
    }

    /** Opens the game's stream; the caller then tells the listener it's connected. */
    private void openGame(String host, int port) {
        connect(GAME_STREAM, host, port);
        gameOpen = true;
        credit = initialCredit;
    }

    private void finish() {
        closed = true;
        unsent.clear();
        unsentBytes = 0;
        srvReply = null;
        NetConnection current = socket;
        if (current != null) {
            current.close();
        }
    }

    /** The proxy's reason for closing a stream, for the disconnect screen. */
    static String reason(int code) {
        return switch (code) {
            case 0x41 -> "The Wisp proxy couldn't open the connection";
            case 0x42 -> "The Wisp proxy couldn't reach the server";
            case 0x43 -> "The Wisp proxy timed out connecting to the server";
            case 0x44 -> "The server refused the Wisp proxy's connection";
            case 0x47 -> "The connection through the Wisp proxy timed out";
            case 0x48 -> "The Wisp proxy blocks this server";
            case 0x49 -> "The Wisp proxy is limiting connections; try again soon";
            case 0x03 -> "The Wisp proxy lost the connection";
            default -> "The Wisp proxy closed the connection (reason " + code + ")";
        };
    }

    /** Hears the proxy's WebSocket, one message (one Wisp packet) at a time. */
    private final class SocketListener implements Listener {

        @Override
        public void connected() {
            // Nothing goes out before the proxy's first CONTINUE says how much it buffers.
        }

        @Override
        public void received(ByteBuffer message) {
            ByteBuffer in = message.order(ByteOrder.LITTLE_ENDIAN);
            if (in.remaining() < HEADER) {
                return;
            }
            byte type = in.get();
            int stream = in.getInt();
            ByteBuffer payload = in.slice();
            boolean connected = false;
            @Nullable Throwable closedBy = null;
            boolean close = false;
            @Nullable ByteBuffer data = null;
            synchronized (WispConnection.this) {
                if (closed) {
                    return;
                }
                switch (type) {
                    case CONTINUE -> {
                        if (payload.remaining() < 4) {
                            return;
                        }
                        int granted = payload.order(ByteOrder.LITTLE_ENDIAN).getInt();
                        if (stream == 0 && initialCredit < 0) {
                            initialCredit = granted;
                            connected = start();
                        } else if (stream == GAME_STREAM) {
                            credit = granted;
                            sendUnsent();
                        }
                    }
                    case DATA -> {
                        if (stream == GAME_STREAM && gameOpen) {
                            data = payload;
                        } else if (stream == SRV_STREAM && srvReply != null) {
                            connected = srvData(payload);
                        }
                    }
                    case CLOSE -> {
                        int code = payload.hasRemaining() ? payload.get() & 0xFF : 0;
                        if (stream == GAME_STREAM && gameOpen) {
                            close = true;
                            closedBy = code == CLOSE_VOLUNTARY ? null : new IOException(reason(code));
                            finish();
                        } else if (stream == SRV_STREAM && srvReply != null) {
                            // No answer from the resolver: join the address as written.
                            srvReply = null;
                            openGame(address.getHostString(), address.getPort());
                            connected = true;
                        } else if (stream == 0) {
                            // The proxy turned the whole connection down.
                            close = true;
                            closedBy = new IOException(reason(code));
                            finish();
                        }
                    }
                    default -> {
                        // INFO and anything newer: v2's, which a v1 client ignores.
                    }
                }
            }
            if (connected) {
                listener.connected();
                flush();
            }
            if (data != null) {
                listener.received(data);
            }
            if (close) {
                listener.closed(closedBy);
            }
        }

        /** After the first CONTINUE: looks up the SRV record, or opens the game's stream at once. */
        private boolean start() {
            String record = address.lookUpSrv() ? SrvLookup.recordName(address.getHostString()) : null;
            if (record == null) {
                openGame(address.getHostString(), address.getPort());
                return true;
            }
            srvReply = new byte[512];
            srvReplyCount = 0;
            connect(SRV_STREAM, SrvLookup.RESOLVER_HOST, SrvLookup.RESOLVER_PORT);
            sendPacket(DATA, SRV_STREAM, SrvLookup.query(record, SRV_QUERY_ID));
            return false;
        }

        /** More of the resolver's reply; once it's whole, opens the game's stream where it points. */
        private boolean srvData(ByteBuffer payload) {
            byte[] reply = srvReply;
            if (reply == null) {
                return false;
            }
            int needed = srvReplyCount + payload.remaining();
            if (needed > reply.length) {
                reply = Arrays.copyOf(reply, Math.max(needed, reply.length * 2));
            }
            payload.get(reply, srvReplyCount, payload.remaining());
            srvReplyCount = needed;
            srvReply = reply;
            int length = SrvLookup.replyLength(reply, srvReplyCount);
            if (length < 0 || srvReplyCount < 2 + length) {
                return false;
            }
            SrvLookup.Target target = SrvLookup.parse(Arrays.copyOfRange(reply, 2, 2 + length), SRV_QUERY_ID);
            srvReply = null;
            sendPacket(CLOSE, SRV_STREAM, new byte[] {CLOSE_VOLUNTARY});
            if (target != null) {
                openGame(target.host(), target.port());
            } else {
                openGame(address.getHostString(), address.getPort());
            }
            return true;
        }

        @Override
        public void closed(@Nullable Throwable cause) {
            boolean tell;
            boolean wasOpen;
            synchronized (WispConnection.this) {
                tell = !closed;
                wasOpen = gameOpen;
                closed = true;
                unsent.clear();
                srvReply = null;
            }
            if (tell) {
                Throwable reason = cause != null || wasOpen
                        ? cause
                        : new IOException("Couldn't reach the Wisp proxy " + address.proxy());
                listener.closed(reason);
            }
        }
    }
}
