package incari.tools.wsbridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The bridge between a raw Eagler client (the JDK's WebSocket) and a scripted vanilla server. */
class WebSocketBridgeTest {

    private ServerSocket server;
    private WebSocketBridge bridge;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        bridge = WebSocketBridge.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                (InetSocketAddress) server.getLocalSocketAddress(),
                message -> {});
    }

    @AfterEach
    void stop() throws IOException {
        bridge.close();
        server.close();
        http.close();
    }

    @Test
    void logsInToTheServerAndThenBridgesPackets() throws Exception {
        Messages messages = new Messages();
        WebSocket client = connect("/any/path", messages);

        client.sendBinary(clientVersion(47), true).get(5, TimeUnit.SECONDS);
        ByteBuf version = messages.next();
        assertThat((int) version.readUnsignedByte()).isEqualTo(0x02);
        assertThat(version.readUnsignedShort()).isEqualTo(5);
        assertThat(version.readUnsignedShort()).isEqualTo(47);

        client.sendBinary(requestLogin("Steve"), true).get(5, TimeUnit.SECONDS);
        try (Socket backend = server.accept()) {
            DataInputStream in = new DataInputStream(backend.getInputStream());
            ByteBuf handshake = readFrame(in);
            assertThat(Packets.readVarInt(handshake)).isEqualTo(0x00);
            assertThat(Packets.readVarInt(handshake)).isEqualTo(47);
            ByteBuf start = readFrame(in);
            assertThat(Packets.readVarInt(start)).isEqualTo(0x00);
            assertThat(Packets.readString(start)).isEqualTo("Steve");
            assertThat(start.isReadable()).isFalse(); // no UUID before 1.20.2

            // Login Success (1.8: the UUID as a string), then the first play packet at once.
            UUID uuid = Packets.offlineUuid("Steve");
            OutputStream out = backend.getOutputStream();
            ByteBuf success = Unpooled.buffer();
            Packets.writeVarInt(success, 0x02);
            Packets.writeString(success, uuid.toString());
            Packets.writeString(success, "Steve");
            writeFrame(out, success);
            writeFrame(out, Unpooled.wrappedBuffer(new byte[] {0x01, 42}));

            ByteBuf allow = messages.next();
            assertThat((int) allow.readUnsignedByte()).isEqualTo(0x05);
            assertThat(Packets.readByteAscii(allow)).isEqualTo("Steve");
            assertThat(new UUID(allow.readLong(), allow.readLong())).isEqualTo(uuid);

            client.sendBinary(ByteBuffer.wrap(new byte[] {0x07, 0x00}), true).get(5, TimeUnit.SECONDS);
            client.sendBinary(ByteBuffer.wrap(new byte[] {0x08}), true).get(5, TimeUnit.SECONDS);
            assertThat((int) messages.next().readUnsignedByte()).isEqualTo(0x09);
            ByteBuf held = messages.next();
            assertThat(held.readableBytes()).isEqualTo(2);
            assertThat((int) held.getByte(1)).isEqualTo(42);

            // Packets both ways; Eagler's own messages stay with the bridge.
            client.sendBinary(ByteBuffer.wrap(new byte[] {(byte) 0xEE, 0x03}), true)
                    .get(5, TimeUnit.SECONDS);
            client.sendBinary(ByteBuffer.wrap(new byte[] {0x05, 0x06}), true).get(5, TimeUnit.SECONDS);
            ByteBuf forwarded = readFrame(in);
            assertThat(forwarded.readableBytes()).isEqualTo(2);
            assertThat((int) forwarded.getByte(0)).isEqualTo(0x05);
        }
        assertThat(messages.closed.get(5, TimeUnit.SECONDS)).isEqualTo(WebSocket.NORMAL_CLOSURE);
    }

    @Test
    void aModernClientSendsItsUuidAndAcknowledgesTheLogin() throws Exception {
        Messages messages = new Messages();
        WebSocket client = connect("/", messages);
        client.sendBinary(clientVersion(777), true).get(5, TimeUnit.SECONDS);
        messages.next();
        client.sendBinary(requestLogin("Alex"), true).get(5, TimeUnit.SECONDS);
        try (Socket backend = server.accept()) {
            DataInputStream in = new DataInputStream(backend.getInputStream());
            readFrame(in);
            ByteBuf start = readFrame(in);
            Packets.readVarInt(start);
            assertThat(Packets.readString(start)).isEqualTo("Alex");
            assertThat(new UUID(start.readLong(), start.readLong())).isEqualTo(Packets.offlineUuid("Alex"));

            ByteBuf success = Unpooled.buffer();
            Packets.writeVarInt(success, 0x02);
            Packets.writeUuid(success, Packets.offlineUuid("Alex"));
            Packets.writeString(success, "Alex");
            Packets.writeVarInt(success, 0);
            writeFrame(backend.getOutputStream(), success);
            assertThat((int) messages.next().readUnsignedByte()).isEqualTo(0x05);

            client.sendBinary(ByteBuffer.wrap(new byte[] {0x08}), true).get(5, TimeUnit.SECONDS);

            ByteBuf acknowledged = readFrame(in);
            assertThat(Packets.readVarInt(acknowledged)).isEqualTo(0x03);
            assertThat((int) messages.next().readUnsignedByte()).isEqualTo(0x09);
        }
    }

    @Test
    void theMotdComesFromTheServersStatus() throws Exception {
        Messages messages = new Messages();
        connect("/", messages).sendText("Accept: motd", true).get(5, TimeUnit.SECONDS);
        try (Socket backend = server.accept()) {
            DataInputStream in = new DataInputStream(backend.getInputStream());
            ByteBuf handshake = readFrame(in);
            Packets.readVarInt(handshake);
            Packets.readVarInt(handshake);
            Packets.readString(handshake);
            handshake.readShort();
            assertThat(Packets.readVarInt(handshake)).isEqualTo(1);
            readFrame(in);
            ByteBuf status = Unpooled.buffer();
            Packets.writeVarInt(status, 0x00);
            Packets.writeString(
                    status,
                    "{\"version\":{\"name\":\"1.12.2\",\"protocol\":340},"
                            + "\"players\":{\"max\":20,\"online\":1,\"sample\":[{\"name\":\"Notch\",\"id\":\"x\"}]},"
                            + "\"description\":{\"text\":\"A \",\"extra\":[{\"text\":\"server\",\"color\":\"gold\"}]}}");
            writeFrame(backend.getOutputStream(), status);

            String answer = messages.texts.poll(5, TimeUnit.SECONDS);
            assertThat(answer)
                    .contains("\"type\":\"motd\"")
                    .contains("\"motd\":[\"A §6server\"]")
                    .contains("\"online\":1")
                    .contains("\"max\":20")
                    .contains("\"players\":[\"Notch\"]")
                    .contains("\"icon\":false")
                    .contains("\"protocol\":340");
        }
    }

    private WebSocket connect(String path, Messages messages) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + bridge.address().getPort() + path);
        return http.newWebSocketBuilder().buildAsync(uri, messages).get(5, TimeUnit.SECONDS);
    }

    private static ByteBuffer clientVersion(int protocol) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(0x01);
        out.writeByte(0x02);
        out.writeShort(1);
        out.writeShort(5);
        out.writeShort(1);
        out.writeShort(protocol);
        Packets.writeByteAscii(out, "Test");
        Packets.writeByteAscii(out, "1");
        out.writeBoolean(false);
        Packets.writeByteAscii(out, "Steve");
        return out.nioBuffer();
    }

    private static ByteBuffer requestLogin(String name) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(0x04);
        Packets.writeByteAscii(out, name);
        Packets.writeByteAscii(out, "");
        out.writeByte(0);
        out.writeBoolean(false);
        out.writeByte(0);
        Packets.writeVarInt(out, 0);
        out.writeByte(0);
        return out.nioBuffer();
    }

    private static ByteBuf readFrame(DataInputStream in) throws IOException {
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.readUnsignedByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        byte[] packet = new byte[length];
        in.readFully(packet);
        return Unpooled.wrappedBuffer(packet);
    }

    private static void writeFrame(OutputStream out, ByteBuf packet) throws IOException {
        ByteBuf framed = Unpooled.buffer();
        VarIntFrames.writeLength(framed, packet.readableBytes());
        framed.writeBytes(packet);
        byte[] bytes = new byte[framed.readableBytes()];
        framed.readBytes(bytes);
        out.write(bytes);
        out.flush();
    }

    /** Collects whole messages (binary as buffers, text as strings) and the close code. */
    private static final class Messages implements WebSocket.Listener {
        final BlockingQueue<ByteBuf> received = new LinkedBlockingQueue<>();
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private final StringBuilder text = new StringBuilder();

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            current.writeBytes(chunk);
            if (last) {
                received.add(Unpooled.wrappedBuffer(current.toByteArray()));
                current.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                texts.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        ByteBuf next() throws InterruptedException {
            ByteBuf message = received.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("a message within 5 s").isNotNull();
            return message;
        }
    }
}
