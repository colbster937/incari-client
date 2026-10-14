package incari.tools.wsbridge;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The server side of one Eagler connection, as EaglerXServer runs it: Eagler's handshake with the
 * client over the WebSocket, then a vanilla login to the Java server over TCP (the handshake and
 * Login Start in the version the client asked for), then packets both ways. Everything runs on the
 * WebSocket's event loop. Eagler's own messages from the client ({@code 0xEE}: skin requests) are
 * dropped: the bridge has no skins to give.
 */
// Writes and closes are fire-and-forget: a failed one closes its channel, which is logged.
@SuppressWarnings("FutureReturnValueIgnored")
final class EaglerConnection {

    private static final int CLIENT_VERSION = 0x01;
    private static final int SERVER_VERSION = 0x02;
    private static final int VERSION_MISMATCH = 0x03;
    private static final int CLIENT_REQUEST_LOGIN = 0x04;
    private static final int SERVER_ALLOW_LOGIN = 0x05;
    private static final int CLIENT_PROFILE_DATA = 0x07;
    private static final int CLIENT_FINISH_LOGIN = 0x08;
    private static final int SERVER_FINISH_LOGIN = 0x09;
    private static final int SERVER_ERROR = 0xFF;
    private static final int ERROR_CUSTOM_MESSAGE = 0x08;

    /** 1.20.2: the client acknowledges its login before configuration. */
    private static final int LOGIN_ACKNOWLEDGED = 764;

    private enum State {
        AWAITING_VERSION,
        AWAITING_LOGIN,
        LOGGING_IN,
        AWAITING_FINISH,
        PLAY,
        CLOSED
    }

    private final Channel webSocket;
    private final InetSocketAddress target;
    private final int id;
    private final Consumer<String> log;
    /** The server's packets between its Login Success and the client's finish. */
    private final Queue<ByteBuf> held = new ArrayDeque<>();

    private State state = State.AWAITING_VERSION;
    private int protocol;
    private @Nullable Channel tcp;
    /** The server's compression threshold once it turns compression on; -1 while it's off. */
    private int compression = -1;

    EaglerConnection(Channel webSocket, InetSocketAddress target, int id, Consumer<String> log) {
        this.webSocket = webSocket;
        this.target = target;
        this.id = id;
        this.log = log;
    }

    /** One binary message from the client. */
    void fromClient(ByteBuf message) {
        try {
            switch (state) {
                case AWAITING_VERSION -> clientVersion(message);
                case AWAITING_LOGIN -> requestLogin(message);
                case AWAITING_FINISH -> finishing(message);
                case PLAY -> play(message);
                default -> {
                    // Logging in to the server, or closed: the client has nothing to say yet.
                }
            }
        } catch (IndexOutOfBoundsException e) {
            error("Invalid Packet");
        }
    }

    void close() {
        state = State.CLOSED;
        while (!held.isEmpty()) {
            held.remove().release();
        }
        if (tcp != null) {
            tcp.close();
        }
    }

    private void clientVersion(ByteBuf in) {
        if (in.readUnsignedByte() != CLIENT_VERSION || in.readUnsignedByte() != 0x02) {
            log.accept("#" + id + " not an Eagler V2+ client");
            webSocket.close();
            return;
        }
        boolean v5 = false;
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            v5 |= in.readUnsignedShort() == 5;
        }
        int best = -1;
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            best = Math.max(best, in.readUnsignedShort());
        }
        String brand = Packets.readByteAscii(in);
        String version = Packets.readByteAscii(in);
        if (!v5 || best < 0) {
            ByteBuf out = webSocket.alloc().buffer();
            out.writeByte(VERSION_MISMATCH);
            out.writeShort(1);
            out.writeShort(5);
            out.writeShort(2);
            out.writeShort(0);
            out.writeShort(0xFFFF);
            Packets.writeByteAscii(out, "This bridge speaks Eagler protocol V5 only");
            send(out);
            WebSocketBridge.closeWebSocket(webSocket, WebSocketCloseStatus.NORMAL_CLOSURE);
            return;
        }
        protocol = best;
        log.accept("#" + id + " Eagler client " + brand + " " + version + ", Minecraft protocol " + protocol);
        ByteBuf out = webSocket.alloc().buffer();
        out.writeByte(SERVER_VERSION);
        out.writeShort(5);
        out.writeShort(protocol);
        Packets.writeByteAscii(out, "incari-wsbridge");
        Packets.writeByteAscii(out, "dev");
        out.writeByte(0); // no auth
        out.writeShort(0);
        out.writeBoolean(false);
        send(out);
        state = State.AWAITING_LOGIN;
    }

    private void requestLogin(ByteBuf in) {
        if (in.readUnsignedByte() != CLIENT_REQUEST_LOGIN) {
            error("Wrong Request Login Packet");
            return;
        }
        String name = Packets.readByteAscii(in);
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            error("Invalid Username");
            return;
        }
        state = State.LOGGING_IN;
        UUID uuid = Packets.offlineUuid(name);
        tcp = new Bootstrap()
                .group(webSocket.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new VarIntFrames.Splitter(), new ServerSide());
                    }
                })
                .connect(target)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.accept("#" + id + " can't reach " + target + ": " + future.cause());
                        error("Can't reach the server");
                        return;
                    }
                    Channel server = future.channel();
                    ByteBuf handshake = server.alloc().buffer();
                    Packets.writeVarInt(handshake, 0x00);
                    Packets.writeVarInt(handshake, protocol);
                    Packets.writeString(handshake, target.getHostString());
                    handshake.writeShort(target.getPort());
                    Packets.writeVarInt(handshake, 2); // login
                    toServer(handshake);
                    ByteBuf start = server.alloc().buffer();
                    Packets.writeVarInt(start, 0x00);
                    Packets.writeString(start, name);
                    if (protocol >= LOGIN_ACKNOWLEDGED) {
                        Packets.writeUuid(start, uuid);
                    }
                    toServer(start);
                })
                .channel();
    }

    /** The server accepted the login: the client may finish its own. */
    private void loggedIn(String name, UUID uuid) {
        state = State.AWAITING_FINISH;
        ByteBuf out = webSocket.alloc().buffer();
        out.writeByte(SERVER_ALLOW_LOGIN);
        Packets.writeByteAscii(out, name);
        Packets.writeUuid(out, uuid);
        Packets.writeVarInt(out, 0); // no capabilities
        out.writeByte(0);
        send(out);
    }

    private void finishing(ByteBuf in) {
        switch (in.readUnsignedByte()) {
            case CLIENT_PROFILE_DATA -> {
                // A skin and a cape, which the bridge has no one to show to.
            }
            case CLIENT_FINISH_LOGIN -> {
                if (protocol >= LOGIN_ACKNOWLEDGED) {
                    ByteBuf acknowledged = webSocket.alloc().buffer(1);
                    Packets.writeVarInt(acknowledged, 0x03);
                    toServer(acknowledged);
                }
                ByteBuf out = webSocket.alloc().buffer(1);
                out.writeByte(SERVER_FINISH_LOGIN);
                send(out);
                state = State.PLAY;
                while (!held.isEmpty()) {
                    webSocket.write(new BinaryWebSocketFrame(held.remove()));
                }
                webSocket.flush();
            }
            default -> error("Wrong Packet");
        }
    }

    private void play(ByteBuf packet) {
        if (packet.getUnsignedByte(packet.readerIndex()) == 0xEE) {
            return;
        }
        toServer(packet.retain());
    }

    private void toServer(ByteBuf packet) {
        Channel server = tcp;
        if (server == null) {
            packet.release();
            return;
        }
        ByteBuf out = server.alloc().buffer(packet.readableBytes() + 8);
        if (compression >= 0) {
            ByteBuf body = Compression.compress(server.alloc(), packet, compression);
            VarIntFrames.writeLength(out, body.readableBytes());
            out.writeBytes(body);
            body.release();
        } else {
            VarIntFrames.writeLength(out, packet.readableBytes());
            out.writeBytes(packet);
        }
        packet.release();
        server.writeAndFlush(out);
    }

    private void send(ByteBuf message) {
        webSocket.writeAndFlush(new BinaryWebSocketFrame(message));
    }

    /** Refuses the client with {@code message}, which it shows as the reason. */
    private void error(String message) {
        if (state == State.CLOSED) {
            return;
        }
        log.accept("#" + id + " refused: " + message);
        ByteBuf out = webSocket.alloc().buffer();
        out.writeByte(SERVER_ERROR);
        out.writeByte(ERROR_CUSTOM_MESSAGE);
        Packets.writeShortUtf8(out, message);
        webSocket.writeAndFlush(new BinaryWebSocketFrame(out));
        WebSocketBridge.closeWebSocket(webSocket, WebSocketCloseStatus.NORMAL_CLOSURE);
        close();
    }

    /** The Java server's side: its login, then its packets as messages. */
    private final class ServerSide extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            ByteBuf packet = (ByteBuf) message;
            if (compression >= 0) {
                ByteBuf inflated = Compression.decompress(ctx.alloc(), packet);
                packet.release();
                packet = inflated;
            }
            if (state == State.PLAY) {
                webSocket.writeAndFlush(new BinaryWebSocketFrame(packet));
                return;
            }
            if (state == State.AWAITING_FINISH) {
                held.add(packet);
                return;
            }
            try {
                login(packet);
            } catch (IndexOutOfBoundsException e) {
                error("The server sent a malformed login packet");
            } finally {
                packet.release();
            }
        }

        private void login(ByteBuf packet) {
            int id = Packets.readVarInt(packet);
            switch (id) {
                case 0x00 -> error(Packets.readString(packet));
                case 0x01 -> error("The server is in online mode; the bridge needs online-mode=false");
                case 0x02 -> {
                    UUID uuid;
                    if (protocol >= 735) {
                        uuid = new UUID(packet.readLong(), packet.readLong());
                    } else {
                        uuid = UUID.fromString(Packets.readString(packet));
                    }
                    loggedIn(Packets.readString(packet), uuid);
                }
                // Set Compression: the link to the server is compressed from here; the client's isn't.
                case 0x03 -> compression = Packets.readVarInt(packet);
                default -> error("The server sent login packet " + id + ", which the bridge doesn't handle");
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.accept("#" + id + " server closed the connection");
            WebSocketBridge.closeWebSocket(webSocket, WebSocketCloseStatus.NORMAL_CLOSURE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.accept("#" + id + " server connection error: " + cause);
            ctx.close();
        }
    }
}
