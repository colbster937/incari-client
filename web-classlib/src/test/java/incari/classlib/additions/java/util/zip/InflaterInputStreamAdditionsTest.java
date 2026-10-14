package incari.classlib.additions.java.util.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;

class InflaterInputStreamAdditionsTest {

    @Test
    void skipsAndReadsInTurnAsAScanDoes() throws IOException {
        Random random = new Random(9);
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (random.nextInt(5) == 0 ? random.nextInt(256) : i % 29);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) {
            out.write(data);
        }

        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            int position = 0;
            while (position < data.length) {
                int skip = Math.min(data.length - position, random.nextInt(3000));
                assertThat(InflaterInputStreamAdditions.skip(in, skip)).isEqualTo(skip);
                position += skip;
                int length = Math.min(data.length - position, 1 + random.nextInt(100));
                byte[] read = in.readNBytes(length);
                assertThat(read).isEqualTo(Arrays.copyOfRange(data, position, position + length));
                position += length;
            }
            assertThat(InflaterInputStreamAdditions.skip(in, 10)).isZero();
        }
    }
}
