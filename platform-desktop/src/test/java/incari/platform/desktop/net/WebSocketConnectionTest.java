package incari.platform.desktop.net;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.PlatformLog;
import incari.platform.api.net.NetConnection;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("FutureReturnValueIgnored")
class WebSocketConnectionTest {

    private final DesktopNetwork network = new DesktopNetwork(PlatformLog.NONE);
    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    /** Every binary message the server received, as text. */
    private final List<String> serverReceived = new CopyOnWriteArrayList<>();

    private @Nullable Channel server;

    @BeforeEach
    void startServer() throws InterruptedException {
        server = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(65536))
                                .addLast(new WebSocketServerProtocolHandler("/game"))
                                .addLast(new WebSocketFrameAggregator(1 << 24))
                                .addLast(new ScriptedServer());
                    }
                })
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .sync()
                .channel();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close().syncUninterruptibly();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private String url() {
        InetSocketAddress address =
                (InetSocketAddress) java.util.Objects.requireNonNull(server).localAddress();
        return "ws://127.0.0.1:" + address.getPort() + "/game";
    }

    @Test
    void eachSendIsOneMessageAndEachMessageArrivesWhole() throws Exception {
        Recorder recorder = new Recorder();
        NetConnection connection = network.openWebSocket(url(), recorder);
        // Sent before the socket opens: queued, then sent in order.
        connection.send(text("one"));
        connection.send(text("two"));

        assertThat(recorder.connected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.isOpen()).isTrue();
        connection.send(text("fragmented"));

        // The server echoes each message; "fragmented" comes back split into three frames.
        assertThat(recorder.next()).isEqualTo("echo:one");
        assertThat(recorder.next()).isEqualTo("echo:two");
        assertThat(recorder.next()).isEqualTo("echo:fragmented");
        assertThat(serverReceived).containsExactly("one", "two", "fragmented");
    }

    @Test
    void textMessagesGoBothWaysApartFromBinaryOnes() throws Exception {
        Recorder recorder = new Recorder();
        NetConnection connection = network.openWebSocket(url(), recorder);
        // Queued before the socket opens, in order with the binary message.
        connection.sendText("Accept: motd");
        connection.send(text("binary"));

        assertThat(recorder.connected.await(5, TimeUnit.SECONDS)).isTrue();
        // The server greets with a text message, then echoes each message in its own kind.
        assertThat(recorder.nextText()).isEqualTo("hello");
        assertThat(recorder.nextText()).isEqualTo("text-echo:Accept: motd");
        assertThat(recorder.next()).isEqualTo("echo:binary");
        assertThat(serverReceived).containsExactly("text:Accept: motd", "binary");
    }

    @Test
    void closingIsAnOrderlyCloseReportedOnce() throws Exception {
        Recorder recorder = new Recorder();
        NetConnection connection = network.openWebSocket(url(), recorder);
        assertThat(recorder.connected.await(5, TimeUnit.SECONDS)).isTrue();

        connection.close();
        connection.close();

        assertThat(recorder.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.cause).isNull();
        assertThat(connection.isOpen()).isFalse();
        Thread.sleep(200);
        assertThat(recorder.closedCalls.get()).isEqualTo(1);
    }

    @Test
    void aServerCloseEndsTheConnection() throws Exception {
        Recorder recorder = new Recorder();
        NetConnection connection = network.openWebSocket(url(), recorder);
        assertThat(recorder.connected.await(5, TimeUnit.SECONDS)).isTrue();

        connection.send(text("bye"));

        assertThat(recorder.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.cause).isNull();
        assertThat(recorder.closedCalls.get()).isEqualTo(1);
    }

    @Test
    void aFailedHandshakeFailsTheConnection() throws Exception {
        Recorder recorder = new Recorder();

        network.openWebSocket(url().replace("/game", "/nothing-here"), recorder);

        assertThat(recorder.closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.connected.getCount()).isEqualTo(1);
        assertThat(recorder.cause).isInstanceOf(IOException.class);
    }

    @Test
    void aRefusedConnectionFailsTheConnection() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Recorder recorder = new Recorder();

        network.openWebSocket("ws://127.0.0.1:" + port + "/", recorder);

        assertThat(recorder.closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.cause).isNotNull();
    }

    private static ByteBuffer text(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Echoes binary messages as binary and text as text, greets with one text message, and closes
     * on "bye".
     * A request for any other path gets a 404.
     */
    private final class ScriptedServer extends SimpleChannelInboundHandler<Object> {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                ctx.writeAndFlush(new TextWebSocketFrame("hello"));
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object received) {
            if (received instanceof FullHttpRequest request) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.NOT_FOUND))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (received instanceof TextWebSocketFrame textFrame) {
                serverReceived.add("text:" + textFrame.text());
                ctx.writeAndFlush(new TextWebSocketFrame("text-echo:" + textFrame.text()));
                return;
            }
            if (!(received instanceof BinaryWebSocketFrame frame)) {
                return;
            }
            String message = frame.content().toString(StandardCharsets.UTF_8);
            serverReceived.add(message);
            if (message.equals("bye")) {
                ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            byte[] reply = ("echo:" + message).getBytes(StandardCharsets.UTF_8);
            if (message.equals("fragmented")) {
                ctx.write(new BinaryWebSocketFrame(false, 0, Unpooled.wrappedBuffer(reply, 0, 5)));
                ctx.write(new ContinuationWebSocketFrame(false, 0, Unpooled.wrappedBuffer(reply, 5, 3)));
                ByteBuf rest = Unpooled.wrappedBuffer(reply, 8, reply.length - 8);
                ctx.writeAndFlush(new ContinuationWebSocketFrame(true, 0, rest));
            } else {
                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(reply)));
            }
        }
    }

    /** Records what a connection reports. */
    private static final class Recorder implements NetConnection.Listener {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final AtomicInteger closedCalls = new AtomicInteger();
        volatile @Nullable Throwable cause;

        @Override
        public void connected() {
            connected.countDown();
        }

        @Override
        public void received(ByteBuffer data) {
            messages.add(StandardCharsets.UTF_8.decode(data).toString());
        }

        @Override
        public void receivedText(String text) {
            texts.add(text);
        }

        @Override
        public void closed(@Nullable Throwable why) {
            cause = why;
            closedCalls.incrementAndGet();
            closed.countDown();
        }

        String next() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("a message within 5 s").isNotNull();
            return message;
        }

        String nextText() throws InterruptedException {
            String message = texts.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("a text message within 5 s").isNotNull();
            return message;
        }
    }
}
