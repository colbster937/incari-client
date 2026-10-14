package incari.classlib.substitutes.java.util.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

class TDeflaterTest {

    @Test
    void streamsOfAnyShapeComeBackWhole() throws DataFormatException {
        Random random = new Random(26);
        for (int trial = 0; trial < 60; trial++) {
            byte[] data = sample(random, 1 + random.nextInt(200_000));
            TDeflater deflater = new TDeflater();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 + random.nextInt(600)];
            int position = 0;
            // Fed as DeflaterOutputStream feeds it: single bytes and runs of any length, each
            // deflated until the deflater asks for more.
            while (position < data.length) {
                int length = Math.min(data.length - position, random.nextInt(3) == 0 ? 1 : random.nextInt(9000) + 1);
                deflater.setInput(data, position, length);
                while (!deflater.needsInput()) {
                    compressed.write(buffer, 0, deflater.deflate(buffer));
                }
                position += length;
            }
            deflater.finish();
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }

            assertThat(inflate(compressed.toByteArray(), false)).isEqualTo(data);
            assertThat(deflater.getBytesRead()).isEqualTo(data.length);
            assertThat(deflater.getBytesWritten()).isEqualTo(compressed.size());
        }
    }

    @Test
    void deflatingWithNothingToDoWritesNothing() {
        // zlib answers "no progress possible" here; TeaVM's Deflater threw on it.
        TDeflater deflater = new TDeflater();
        byte[] buffer = new byte[64];
        deflater.setInput(new byte[] {1, 2, 3});
        deflater.deflate(buffer);

        assertThat(deflater.needsInput()).isTrue();
        assertThat(deflater.deflate(buffer)).isZero();
        assertThat(deflater.deflate(buffer, 0, buffer.length, TDeflater.SYNC_FLUSH))
                .isPositive();
        assertThat(deflater.deflate(buffer, 0, buffer.length, TDeflater.SYNC_FLUSH))
                .isZero();
    }

    @Test
    void aSyncFlushHandsOutEverythingSoFar() throws DataFormatException {
        byte[] data = sample(new Random(3), 5000);
        TDeflater deflater = new TDeflater(TDeflater.DEFAULT_COMPRESSION, true);
        byte[] buffer = new byte[10_000];
        deflater.setInput(data);
        int length = deflater.deflate(buffer, 0, buffer.length, TDeflater.SYNC_FLUSH);

        Inflater inflater = new Inflater(true);
        inflater.setInput(buffer, 0, length);
        byte[] out = new byte[data.length];
        assertThat(inflater.inflate(out)).isEqualTo(data.length);
        assertThat(out).isEqualTo(data);
    }

    @Test
    void resetStartsANewStreamAsPacketCompressionReusesIt() throws DataFormatException {
        TDeflater deflater = new TDeflater();
        Random random = new Random(4);
        for (int packet = 0; packet < 5; packet++) {
            byte[] data = sample(random, 100 + random.nextInt(3000));
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.reset();

            assertThat(inflate(compressed.toByteArray(), false)).isEqualTo(data);
        }
    }

    @Test
    void aNewLevelAppliesMidStream() throws DataFormatException {
        byte[] data = sample(new Random(5), 50_000);
        TDeflater deflater = new TDeflater(TDeflater.BEST_SPEED);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        deflater.setInput(data, 0, 25_000);
        while (!deflater.needsInput()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.setLevel(TDeflater.NO_COMPRESSION);
        deflater.setInput(data, 25_000, 25_000);
        deflater.finish();
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }

        assertThat(inflate(compressed.toByteArray(), false)).isEqualTo(data);
    }

    private static byte[] inflate(byte[] compressed, boolean nowrap) throws DataFormatException {
        Inflater inflater = new Inflater(nowrap);
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!inflater.finished()) {
            int n = inflater.inflate(buffer);
            if (n == 0 && inflater.needsInput()) {
                break;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Mostly a repeating pattern, a fifth noise: compressible, as chunk data is. */
    private static byte[] sample(Random random, int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (random.nextInt(5) == 0 ? random.nextInt(256) : i % 37);
        }
        return data;
    }
}
