package incari.tools.wsbridge;

import com.google.gson.JsonObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A development stand-in for an Eagler proxy (EaglerXServer) in front of one Java server: it
 * answers Eagler's MOTD query from the server's status, runs the server side of Eagler's handshake
 * (protocol V5), logs the player in to the server over TCP itself, then bridges packets: each
 * binary message becomes one length-prefixed packet on the TCP side, and each packet the server
 * sends becomes one binary message (ADR 0009, ADR 0016). The server must run with {@code
 * online-mode=false}, as it would behind a real Eagler proxy; if it compresses packets, the bridge
 * decompresses them (Eagler clients never see compression).
 *
 * <p>A development tool, for trying Incari's Eagler transport against vanilla servers of any
 * version. It has no limits or authentication and must never face the internet.
 */
// Writes and closes are fire-and-forget: a failed one closes its channel, which the handlers log.
@SuppressWarnings("FutureReturnValueIgnored")
public final class WebSocketBridge implements AutoCloseable {

    /** The same limit as the client's transport: larger than any packet the game allows. */
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;

    /** How long a client has to answer a close frame before the connection is cut. */
    private static final long CLOSE_TIMEOUT_MILLIS = 2000;

    private final InetSocketAddress target;
    private final Consumer<String> log;
    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    private final AtomicInteger connections = new AtomicInteger();
    private @Nullable Channel server;

    private WebSocketBridge(InetSocketAddress target, Consumer<String> log) {
        this.target = target;
        this.log = log;
    }

    /**
     * Starts listening.
     *
     * @param listen where WebSocket clients connect; port 0 picks a free one
     * @param target the Java server's TCP address
     */
    public static WebSocketBridge start(InetSocketAddress listen, InetSocketAddress target, Consumer<String> log)
            throws InterruptedException {
        WebSocketBridge bridge = new WebSocketBridge(target, log);
        bridge.server = new ServerBootstrap()
                .group(bridge.group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        WebSocketServerProtocolConfig config = WebSocketServerProtocolConfig.newBuilder()
                                .websocketPath("/")
                                // Any path: Eagler-style addresses often carry one.
                                .checkStartsWith(true)
                                .maxFramePayloadLength(MAX_MESSAGE)
                                .build();
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(64 * 1024))
                                .addLast(new WebSocketServerProtocolHandler(config))
                                .addLast(new WebSocketFrameAggregator(MAX_MESSAGE))
                                .addLast(bridge.new WebSocketSide());
                    }
                })
                .bind(listen)
                .sync()
                .channel();
        return bridge;
    }

    /** The address it listens on (with the port chosen, if 0 was asked for). */
    public InetSocketAddress address() {
        return (InetSocketAddress) Objects.requireNonNull(server).localAddress();
    }

    @Override
    public void close() {
        if (server != null) {
            server.close().syncUninterruptibly();
        }
        group.shutdownGracefully().syncUninterruptibly();
    }

    /**
     * One WebSocket client, met the way an Eagler proxy meets it: a text {@code Accept: motd} asks
     * for the MOTD; otherwise the first binary message opens Eagler's handshake (protocol V5), the
     * bridge logs in to the server itself, and only then bridges packets.
     */
    private final class WebSocketSide extends ChannelInboundHandlerAdapter {

        private @Nullable EaglerConnection login;
        private int id;
        private boolean first = true;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (!(event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake)) {
                ctx.fireUserEventTriggered(event);
                return;
            }
            id = connections.incrementAndGet();
            log.accept("#" + id + " WebSocket " + ctx.channel().remoteAddress() + " " + handshake.requestUri());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof WebSocketFrame frame)) {
                ctx.fireChannelRead(message);
                return;
            }
            try {
                if (first) {
                    first = false;
                    if (frame instanceof TextWebSocketFrame text) {
                        answerQuery(ctx.channel(), text.text());
                        return;
                    }
                    login = new EaglerConnection(ctx.channel(), target, id, log);
                }
                EaglerConnection current = login;
                if (frame instanceof BinaryWebSocketFrame
                        && current != null
                        && frame.content().isReadable()) {
                    current.fromClient(frame.content());
                }
            } finally {
                frame.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            EaglerConnection current = login;
            if (current != null) {
                log.accept("#" + id + " WebSocket closed");
                current.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.accept("#" + id + " WebSocket error: " + cause);
            ctx.close();
        }

        private void answerQuery(Channel webSocket, String query) {
            if (!query.trim().equalsIgnoreCase("accept: motd")) {
                log.accept("#" + id + " unknown query: " + query);
                closeWebSocket(webSocket, WebSocketCloseStatus.POLICY_VIOLATION);
                return;
            }
            BackendStatus.query(webSocket.eventLoop(), target).whenComplete((motd, failure) -> {
                if (failure != null) {
                    log.accept("#" + id + " MOTD: " + target + " didn't answer: " + failure);
                    closeWebSocket(webSocket, WebSocketCloseStatus.ENDPOINT_UNAVAILABLE);
                    return;
                }
                JsonObject answer = new JsonObject();
                answer.addProperty("name", "incari-wsbridge");
                answer.addProperty("brand", "incari");
                answer.addProperty("vers", "dev");
                answer.addProperty("cracked", true);
                answer.addProperty("time", System.currentTimeMillis());
                answer.addProperty("type", "motd");
                answer.add("data", motd.toJson());
                webSocket.write(new TextWebSocketFrame(answer.toString()));
                byte[] icon = motd.icon();
                if (icon != null) {
                    webSocket.write(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(icon)));
                }
                webSocket.flush();
                closeWebSocket(webSocket, WebSocketCloseStatus.NORMAL_CLOSURE);
            });
        }
    }

    /**
     * Closes a WebSocket the way the protocol asks: a close frame, then the client's answer closes
     * the connection (the protocol handler does that), with a cut-off for a client that doesn't
     * answer. Dropping the connection right after the frame can reach a browser as an abnormal
     * close (1006), which it reports as an error, ahead of the packets before it (a kick's reason).
     */
    static void closeWebSocket(Channel webSocket, WebSocketCloseStatus status) {
        if (webSocket.isActive()) {
            webSocket.writeAndFlush(new CloseWebSocketFrame(status));
            webSocket.eventLoop().schedule(() -> webSocket.close(), CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}
