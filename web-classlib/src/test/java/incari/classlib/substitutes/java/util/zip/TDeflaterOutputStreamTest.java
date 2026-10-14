package incari.classlib.substitutes.java.util.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;

/** The stream's contract, run over the JDK's Deflater (in the browser build it runs over TDeflater). */
class TDeflaterOutputStreamTest {

    @Test
    void writesOfAnySizeComeBackWhole() throws IOException {
        Random random = new Random(7);
        byte[] data = new byte[300_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (random.nextInt(5) == 0 ? random.nextInt(256) : i % 41);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TDeflaterOutputStream out = new TDeflaterOutputStream(bytes)) {
            int position = 0;
            while (position < data.length) {
                if (random.nextInt(4) == 0) {
                    out.write(data[position++]);
                } else {
                    int length = Math.min(data.length - position, random.nextInt(9000) + 1);
                    out.write(data, position, length);
                    position += length;
                }
            }
        }

        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void aSyncFlushMakesEverythingWrittenReadable() throws IOException {
        byte[] data = "chunk data, flushed".repeat(50).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TDeflaterOutputStream out = new TDeflaterOutputStream(bytes, true);
        out.write(data, 0, data.length);
        out.flush();

        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(bytes.toByteArray());
        byte[] back = new byte[data.length];
        try {
            assertThat(inflater.inflate(back)).isEqualTo(data.length);
        } catch (java.util.zip.DataFormatException e) {
            throw new AssertionError(e);
        }
        assertThat(back).isEqualTo(data);
    }

    @Test
    void writingAfterFinishFails() throws IOException {
        TDeflaterOutputStream out = new TDeflaterOutputStream(new ByteArrayOutputStream(), new Deflater());
        out.finish();

        assertThatThrownBy(() -> out.write(1)).isInstanceOf(IOException.class);
    }
}
