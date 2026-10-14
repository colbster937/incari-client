package incari.net.relay;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** The shared-world host's framing and login, as the game's side of the pipeline sees them. */
class LanStagesTest {

    @Test
    void joinsPiecesAndInflatesCompressedPackets() {
        EmbeddedChannel channel = new EmbeddedChannel();
        LanFraming.install(channel.pipeline());

        channel.writeInbound(bytes(1, 10, 11), bytes(0, 12));
        ByteBuf joined = channel.readInbound();
        assertThat(array(joined)).containsExactly(10, 11, 12);

        byte[] packet = new byte[5000];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = (byte) (i % 7);
        }
        Deflater deflater = new Deflater();
        deflater.setInput(packet);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        while (!deflater.finished()) {
            compressed.write(chunk, 0, deflater.deflate(chunk));
        }
        ByteBuf message = Unpooled.buffer();
        message.writeByte(2).writeInt(packet.length).writeBytes(compressed.toByteArray());
        channel.writeInbound(message);
        ByteBuf inflated = channel.readInbound();
        assertThat(inflated.readableBytes()).isEqualTo(5000);
        assertThat(inflated.getByte(4999)).isEqualTo(packet[4999]);
    }

    @Test
    void sendsBigPacketsInPieces() {
        EmbeddedChannel channel = new EmbeddedChannel();
        LanFraming.install(channel.pipeline());
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[100_000]));

        int total = 0;
        int pieces = 0;
        ByteBuf out;
        int last = -1;
        while ((out = channel.readOutbound()) != null) {
            assertThat(out.readableBytes()).isLessThanOrEqualTo(LanFraming.FRAGMENT);
            last = out.readUnsignedByte();
            total += out.readableBytes();
            pieces++;
            out.release();
        }
        assertThat(total).isEqualTo(100_000);
        assertThat(pieces).isEqualTo(2);
        assertThat(last).isZero();
    }

    @Test
    void speaksTheHostsLogin() {
        EmbeddedChannel channel = new EmbeddedChannel();
        UUID brand = new UUID(1, 2);
        LanLogin.install(
                channel.pipeline(), new LanLogin(new byte[] {1, 0, 0, 0, 0}, new byte[] {1, 0, 0, 0, 0}, brand));

        // Via's 1.8 handshake is dropped; Login Start gains Eagler's fields.
        channel.writeOutbound(bytes(0x00, 47, 0), packet(0x00, "Steve"));
        ByteBuf start = channel.readOutbound();
        assertThat(channel.<Object>readOutbound()).isNull();
        assertThat((int) start.readByte()).isZero();
        assertThat(string(start)).isEqualTo("Steve");
        assertThat((int) start.readByte()).isEqualTo(5);
        start.skipBytes(5);
        assertThat((int) start.readByte()).isEqualTo(5);
        start.skipBytes(5);
        assertThat((int) start.readByte()).isEqualTo(6);
        assertThat(start.readShort()).isEqualTo((short) 2);
        assertThat(start.readShort()).isEqualTo((short) 3);
        assertThat(start.readShort()).isEqualTo((short) 4);
        assertThat(new UUID(start.readLong(), start.readLong())).isEqualTo(brand);
        assertThat(start.isReadable()).isFalse();

        // The host's Login Success loses the Eagler protocol it ends with.
        ByteBuf success = packet(0x02, "00000000-0000-0000-0000-000000000001", "Steve");
        success.writeShort(4);
        channel.writeInbound(success);
        ByteBuf passed = channel.readInbound();
        assertThat((int) passed.readByte()).isEqualTo(2);
        string(passed);
        assertThat(string(passed)).isEqualTo("Steve");
        assertThat(passed.isReadable()).isFalse();
    }

    private static ByteBuf packet(int id, String... strings) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(id);
        for (String s : strings) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.writeByte(b.length).writeBytes(b);
        }
        return out;
    }

    private static String string(ByteBuf in) {
        int length = in.readByte();
        return in.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }

    private static ByteBuf bytes(int... values) {
        ByteBuf out = Unpooled.buffer();
        for (int v : values) {
            out.writeByte(v);
        }
        return out;
    }

    private static int[] array(ByteBuf in) {
        int[] out = new int[in.readableBytes()];
        for (int i = 0; i < out.length; i++) {
            out[i] = in.readUnsignedByte();
        }
        return out;
    }
}
