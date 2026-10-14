package incari.net.eagler;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EaglerTexturesTest {

    @Test
    void skinBytesMatchTheServersConversion() {
        int[] pixels = new int[64 * 64];
        pixels[0] = 0xFF123456;
        pixels[1] = 0x00ABCDEF;

        byte[] data = EaglerTextures.encodeSkin(pixels);

        // EaglerXServer converts ARGB the same way: blue, green, red >> 1 with alpha's top bit.
        assertThat(data[0]).isEqualTo((byte) 0x56);
        assertThat(data[1]).isEqualTo((byte) 0x34);
        assertThat(data[2]).isEqualTo((byte) ((0x12 >> 1) | 0x80));
        assertThat(data[3]).isEqualTo((byte) 0xEF);
        assertThat(data[4]).isEqualTo((byte) 0xCD);
        assertThat(data[5]).isEqualTo((byte) (0xAB >> 1));
    }

    @Test
    void skinsSurviveARoundTripWithinTheFormatsPrecision() {
        int[] pixels = new int[64 * 64];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (i % 3 == 0 ? 0 : 0xFF000000) | ((i * 7919) & 0xFFFFFF);
        }

        int[] decoded = EaglerTextures.decodeSkin(EaglerTextures.encodeSkin(pixels));

        for (int i = 0; i < pixels.length; i++) {
            assertThat(decoded[i] >>> 24).isEqualTo(i % 3 == 0 ? 0 : 0xFF);
            assertThat(decoded[i] & 0xFFFF).isEqualTo(pixels[i] & 0xFFFF);
            assertThat((decoded[i] >>> 17) & 0x7F).isEqualTo((pixels[i] >>> 17) & 0x7F);
        }
        // Red loses its lowest bit, as it does between Eagler clients.
        int[] red = new int[64 * 64];
        Arrays.fill(red, 0xFFFF0000);
        assertThat(EaglerTextures.decodeSkin(EaglerTextures.encodeSkin(red))[0]).isEqualTo(0xFFFE0000);
    }

    @Test
    void capesCarryTheCapeRegionOnly() {
        int[] pixels = new int[64 * 32];
        pixels[0] = 0xFF010203;
        pixels[16 * 64 + 21] = 0xFF0A0B0C; // the 22x17 region's last pixel
        pixels[21 * 64 + 22] = 0xFF112233; // the extra column's last pixel
        pixels[40] = 0xFFFFFFFF; // outside what Eagler sends

        int[] decoded = EaglerTextures.decodeCape(EaglerTextures.encodeCape(pixels));

        assertThat(decoded[0]).isEqualTo(0xFF010203);
        assertThat(decoded[16 * 64 + 21]).isEqualTo(0xFF0A0B0C);
        assertThat(decoded[21 * 64 + 22]).isEqualTo(0xFF112233);
        assertThat(decoded[40]).isZero();
    }

    @Test
    void theAnswerIsMatchedToItsRequest() {
        List<String> seen = new ArrayList<>();
        EaglerTextures textures = new EaglerTextures((player, skin, cape) -> seen.add(player + " " + skin.preset() + " "
                + skin.slim() + " " + (skin.pixels() != null) + " " + cape.preset()));
        List<ByteBuf> sent = new ArrayList<>();
        textures.attach(new EaglerMessages(new EaglerMessages.Sink() {
            @Override
            public ByteBuf allocate() {
                return Unpooled.buffer();
            }

            @Override
            public void write(ByteBuf message) {
                sent.add(message);
            }
        }));
        UUID player = UUID.randomUUID();

        textures.request(player);

        assertThat(sent).hasSize(1);
        ByteBuf request = sent.get(0);
        assertThat((int) request.readUnsignedByte()).isEqualTo(0xEE);
        assertThat((int) request.readUnsignedByte()).isEqualTo(0x03);
        int id = Wire.readVarInt(request);
        assertThat(new UUID(request.readLong(), request.readLong())).isEqualTo(player);

        ByteBuf answer = Unpooled.buffer();
        Wire.writeVarInt(answer, id);
        Wire.writeVarInt(answer, -1 - 1); // a custom skin on the slim model
        Wire.writeVarInt(answer, 0); // no cape
        answer.writeBytes(new byte[EaglerTextures.SKIN_BYTES]);
        assertThat(textures.handle(0x05, answer)).isTrue();

        assertThat(seen).containsExactly(player + " -1 true true 0");
        assertThat(textures.handle(0x7F, Unpooled.buffer())).isFalse();
    }
}
