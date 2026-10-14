package incari.client.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link ChunkCompression}'s streams inflate back to what was written, the fast one or the game's. */
class ChunkCompressionTest {

    @AfterEach
    void off() {
        ChunkCompression.setEnabled(false);
    }

    @Test
    void bothLevelsInflateToTheSameBytes() throws IOException {
        byte[] chunk = chunkLike();
        ChunkCompression.setEnabled(false);
        byte[] game = deflate(chunk);
        ChunkCompression.setEnabled(true);
        byte[] fast = deflate(chunk);

        assertThat(inflate(game)).isEqualTo(chunk);
        assertThat(inflate(fast)).isEqualTo(chunk);
        // The fast stream really is another level: zlib's header says which (FLEVEL, RFC 1950).
        assertThat(game[1] & 0xC0).isEqualTo(0x80);
        assertThat(fast[1] & 0xC0).isEqualTo(0x00);
    }

    /** Runs of repeated block ids with some noise in them, as a chunk's palette data looks. */
    private static byte[] chunkLike() {
        Random random = new Random(26_3);
        byte[] bytes = new byte[64 * 1024];
        int i = 0;
        while (i < bytes.length) {
            byte value = (byte) random.nextInt(12);
            int run = 1 + random.nextInt(200);
            for (int j = 0; j < run && i < bytes.length; j++) {
                bytes[i++] = random.nextInt(20) == 0 ? (byte) random.nextInt() : value;
            }
        }
        return bytes;
    }

    private static byte[] deflate(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream stream = ChunkCompression.deflate(out)) {
            stream.write(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] bytes) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }
}
