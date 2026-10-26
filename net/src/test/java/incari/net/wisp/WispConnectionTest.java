package incari.net.wisp;

import static org.assertj.core.api.Assertions.assertThat;

import incari.net.WebSocketUrl;
import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WispConnectionTest {

    private static final WebSocketUrl PROXY = WebSocketUrl.parse("wss://wisp.example.net/");

    private final FakeProxy proxy = new FakeProxy();
    private final Heard heard = new Heard();

    @Test
    void opensAStreamToTheServerAfterTheFirstContinue() {
        NetConnection connection = WispConnection.open(proxy, new WispAddress(PROXY, "mc.example.com", 25570), heard);

        assertThat(proxy.url).isEqualTo("wss://wisp.example.net/");
        assertThat(proxy.sent).isEmpty();

        proxy.deliver(packet(3, 0, le32(4)));

        assertThat(heard.connected).isTrue();
        assertThat(connection.isOpen()).isTrue();
        assertThat(proxy.sent).hasSize(1);
        ByteBuffer connect = proxy.sent.getFirst();
        assertThat(connect.get()).isEqualTo((byte) 1);
        assertThat(connect.getInt()).isEqualTo(1);
        assertThat(connect.get()).isEqualTo((byte) 1); // TCP
        assertThat(connect.getShort() & 0xFFFF).isEqualTo(25570);
        assertThat(rest(connect)).isEqualTo("mc.example.com");
    }

    @Test
    void sendsWhatAFlushGathersAsOneDataPacket() {
        NetConnection connection = WispConnection.open(proxy, new WispAddress(PROXY, "10.0.0.2", 25565), heard);
        proxy.deliver(packet(3, 0, le32(4)));
        proxy.sent.clear();

        connection.send(ascii("ab"));
        connection.send(ascii("cd"));
        assertThat(proxy.sent).isEmpty();
        connection.flush();

        assertThat(proxy.sent).hasSize(1);
        ByteBuffer data = proxy.sent.getFirst();
        assertThat(data.get()).isEqualTo((byte) 2);
        assertThat(data.getInt()).isEqualTo(1);
        assertThat(rest(data)).isEqualTo("abcd");
    }

    @Test
    void waitsForTheProxyToGrantMorePackets() {
        NetConnection connection = WispConnection.open(proxy, new WispAddress(PROXY, "10.0.0.2", 25565), heard);
        proxy.deliver(packet(3, 0, le32(1)));
        proxy.sent.clear();

        connection.send(ascii("one"));
        connection.flush();
        connection.send(ascii("two"));
        connection.flush();
        assertThat(proxy.sent).hasSize(1);

        proxy.deliver(packet(3, 1, le32(8)));

        assertThat(proxy.sent).hasSize(2);
        ByteBuffer second = proxy.sent.get(1);
        second.position(5);
        assertThat(rest(second)).isEqualTo("two");
    }

    @Test
    void passesTheServersBytesUp() {
        WispConnection.open(proxy, new WispAddress(PROXY, "10.0.0.2", 25565), heard);
        proxy.deliver(packet(3, 0, le32(4)));

        proxy.deliver(packet(2, 1, "hello".getBytes(StandardCharsets.US_ASCII)));

        assertThat(heard.received).containsExactly("hello");
    }

    @Test
    void aRefusedStreamClosesWithTheProxysReason() {
        WispConnection.open(proxy, new WispAddress(PROXY, "10.0.0.2", 25565), heard);
        proxy.deliver(packet(3, 0, le32(4)));

        proxy.deliver(packet(4, 1, new byte[] {0x44}));

        assertThat(heard.closed).isTrue();
        assertThat(heard.cause).hasMessageContaining("refused");
        assertThat(proxy.open).isFalse();
    }

    @Test
    void anUnreachableProxyFailsTheConnection() {
        WispConnection.open(proxy, new WispAddress(PROXY, "10.0.0.2", 25565), heard);

        proxy.listener.closed(null);

        assertThat(heard.connected).isFalse();
        assertThat(heard.cause).hasMessageContaining("wisp.example.net");
    }

    @Test
    void closingSendsCloseAndTellsTheListenerOnce() {
        NetConnection connection = WispConnection.open(proxy, new WispAddress(PROXY, "10.0.0.2", 25565), heard);
        proxy.deliver(packet(3, 0, le32(4)));
        proxy.sent.clear();

        connection.close();
        proxy.listener.closed(null);

        assertThat(proxy.sent).hasSize(1);
        assertThat(proxy.sent.getFirst().get()).isEqualTo((byte) 4);
        assertThat(heard.closedCount).isEqualTo(1);
        assertThat(heard.cause).isNull();
    }

    @Test
    void followsTheServersSrvRecord() {
        WispConnection.open(proxy, new WispAddress(PROXY, "example.com", 25565), heard);
        proxy.deliver(packet(3, 0, le32(4)));

        // The lookup: a stream to the resolver and the query on it.
        assertThat(heard.connected).isFalse();
        assertThat(proxy.sent).hasSize(2);
        ByteBuffer connect = proxy.sent.getFirst();
        connect.position(5);
        connect.get();
        assertThat(connect.getShort() & 0xFFFF).isEqualTo(53);
        assertThat(rest(connect)).isEqualTo("1.1.1.1");
        ByteBuffer query = proxy.sent.get(1);
        query.position(5);
        byte[] framed = new byte[query.remaining()];
        query.get(framed);
        int id = ((framed[2] & 0xFF) << 8) | (framed[3] & 0xFF);
        proxy.sent.clear();

        byte[] reply = SrvLookupTest.reply(id, "_minecraft._tcp.example.com", 0, 5, 25599, "play.example.com");
        ByteBuffer replyFramed = ByteBuffer.allocate(2 + reply.length)
                .putShort((short) reply.length)
                .put(reply);
        byte[] whole = replyFramed.array();
        // In two pieces, as TCP may deliver it.
        proxy.deliver(packet(2, 2, java.util.Arrays.copyOfRange(whole, 0, 7)));
        proxy.deliver(packet(2, 2, java.util.Arrays.copyOfRange(whole, 7, whole.length)));

        assertThat(heard.connected).isTrue();
        ByteBuffer game =
                proxy.sent.stream().filter(p -> p.get(0) == 1).findFirst().orElseThrow();
        game.position(5);
        game.get();
        assertThat(game.getShort() & 0xFFFF).isEqualTo(25599);
        assertThat(rest(game)).isEqualTo("play.example.com");
    }

    @Test
    void noSrvAnswerJoinsTheAddressAsWritten() {
        WispConnection.open(proxy, new WispAddress(PROXY, "example.com", 25565), heard);
        proxy.deliver(packet(3, 0, le32(4)));
        proxy.sent.clear();

        proxy.deliver(packet(4, 2, new byte[] {0x42}));

        assertThat(heard.connected).isTrue();
        ByteBuffer game = proxy.sent.getFirst();
        game.position(8);
        assertThat(rest(game)).isEqualTo("example.com");
    }

    private static ByteBuffer packet(int type, int stream, byte[] payload) {
        ByteBuffer packet = ByteBuffer.allocate(5 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte) type).putInt(stream).put(payload);
        return packet.flip();
    }

    private static byte[] le32(int value) {
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array();
    }

    private static ByteBuffer ascii(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String rest(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The platform's WebSocket to the proxy, played by the test. */
    private static final class FakeProxy implements PlatformNetwork, NetConnection {

        @Nullable
        String url;

        @Nullable
        Listener listener;

        boolean open = true;
        final List<ByteBuffer> sent = new ArrayList<>();

        @Override
        public NetConnection connect(String host, int port, Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NetConnection openWebSocket(String url, Listener listener) {
            this.url = url;
            this.listener = listener;
            listener.connected();
            return this;
        }

        void deliver(ByteBuffer message) {
            listener.received(message);
        }

        @Override
        public void send(ByteBuffer data) {
            sent.add(data.duplicate().order(ByteOrder.LITTLE_ENDIAN));
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String remoteDescription() {
            return String.valueOf(url);
        }
    }

    private static final class Heard implements NetConnection.Listener {

        boolean connected;
        boolean closed;
        int closedCount;

        @Nullable
        Throwable cause;

        final List<String> received = new ArrayList<>();

        @Override
        public void connected() {
            connected = true;
        }

        @Override
        public void received(ByteBuffer data) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (data.hasRemaining()) {
                bytes.write(data.get());
            }
            received.add(bytes.toString(StandardCharsets.US_ASCII));
        }

        @Override
        public void closed(@Nullable Throwable cause) {
            closed = true;
            closedCount++;
            this.cause = cause;
        }
    }
}
