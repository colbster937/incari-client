import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Round-trips data through the browser build's deflate and inflate streams, written the way the
 * game writes chunks and NBT files (a DataOutputStream over a BufferedOutputStream over the
 * compressor: single bytes, short and long runs). TeaVM's own DeflaterOutputStream failed partway
 * through with zlib's "no progress" (-5), and singleplayer's saved chunks didn't read back. Half
 * the trials read back the way a chunk scan does, skipping runs between reads: TeaVM's
 * InflaterInputStream.skip overwrote its own compressed input.
 */
public class DeflateProbe {
    private static final int TRIALS = 60;

    public static void main(String[] args) throws Exception {
        Random random = new Random(26);
        int failures = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            int size = 1 + random.nextInt(trial % 10 == 0 ? 500_000 : 60_000);
            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) {
                data[i] = (byte) (random.nextInt(5) == 0 ? random.nextInt(256) : i % 37);
            }
            boolean gzip = trial % 3 == 0;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream compressor = gzip ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(compressor))) {
                int position = 0;
                while (position < size) {
                    int kind = random.nextInt(4);
                    if (kind == 0) {
                        out.writeByte(data[position++]);
                    } else {
                        int length = Math.min(size - position, 1 + random.nextInt(kind == 1 ? 16 : 20_000));
                        out.write(data, position, length);
                        position += length;
                    }
                    if (random.nextInt(50) == 0) {
                        out.flush();
                    }
                }
            }
            byte[] back;
            try (InputStream in = gzip
                    ? new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))
                    : new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                ByteArrayOutputStream collected = new ByteArrayOutputStream();
                byte[] buffer = new byte[1 + random.nextInt(8192)];
                boolean skipping = trial % 2 == 1;
                int n;
                while (true) {
                    if (skipping && random.nextBoolean()) {
                        // Skipped bytes are compared as zeros: the expected data gets the same.
                        int length = random.nextInt(3000);
                        long skipped = in.skip(length);
                        int start = collected.size();
                        for (long k = 0; k < skipped; k++) {
                            collected.write(0);
                        }
                        Arrays.fill(data, Math.min(start, data.length), Math.min(start + (int) skipped, data.length), (byte) 0);
                        if (skipped < length) {
                            break;
                        }
                        continue;
                    }
                    if ((n = in.read(buffer)) <= 0) {
                        break;
                    }
                    collected.write(buffer, 0, n);
                }
                back = collected.toByteArray();
            } catch (Exception e) {
                failures++;
                System.out.println("trial " + trial + " (" + size + " bytes, gzip " + gzip + ") failed to read: " + e);
                continue;
            }
            if (!Arrays.equals(back, data)) {
                failures++;
                System.out.println("trial " + trial + " (" + size + " bytes, gzip " + gzip + ") came back as " + back.length
                        + " different bytes");
            }
        }
        if (failures == 0) {
            System.out.println(TRIALS + " deflate round trips came back whole");
            System.out.println("probe passed");
        } else {
            System.out.println("probe failed: " + failures + " of " + TRIALS + " round trips");
        }
    }
}
