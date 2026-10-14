package incari.classlib.substitutes.java.util.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class TGZIPOutputStreamTest {

    @Test
    void writesWhatTheJdkWrites() throws IOException {
        byte[] data = sample();
        ByteArrayOutputStream ours = new ByteArrayOutputStream();
        try (TGZIPOutputStream gzip = new TGZIPOutputStream(ours)) {
            gzip.write(data, 0, data.length);
        }
        ByteArrayOutputStream jdk = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(jdk)) {
            gzip.write(data, 0, data.length);
        }

        assertThat(ours.toByteArray()).isEqualTo(jdk.toByteArray());
    }

    @Test
    void flushesAnyNumberOfTimes() throws IOException {
        // NbtIo's close goes through two buffered streams, each flushing the gzip stream.
        byte[] data = "level data".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TGZIPOutputStream gzip = new TGZIPOutputStream(bytes)) {
            gzip.flush();
            gzip.write(data, 0, data.length);
            gzip.flush();
            gzip.flush();
        }

        assertThat(gunzip(bytes.toByteArray())).isEqualTo(data);
    }

    @Test
    void emptyStreamIsValidGzip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new TGZIPOutputStream(bytes).close();

        assertThat(gunzip(bytes.toByteArray())).isEmpty();
    }

    private static byte[] sample() {
        // Compressible but not trivially: repeated words with some noise.
        Random random = new Random(26);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            text.append(i % 7 == 0 ? "minecraft:stone" : "minecraft:air").append(random.nextInt(50));
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }
}
