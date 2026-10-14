package incari.classlib.additions.java.util.zip;

import incari.classlib.InstanceMethod;
import incari.classlib.Replaces;
import java.io.IOException;
import java.util.zip.InflaterInputStream;

/** {@code java.util.zip.InflaterInputStream} methods TeaVM has but gets wrong. */
public final class InflaterInputStreamAdditions {

    /** Inflated bytes a skip reads at most at a time. */
    private static final int SKIP_CHUNK = 512;

    private InflaterInputStreamAdditions() {}

    /**
     * Skips {@code n} inflated bytes by reading them into a scratch array of its own. TeaVM's read
     * them into the stream's {@code buf}, the compressed input the inflater was still taking from
     * (it holds the array, not a copy): every skip overwrote compressed data not yet inflated, and
     * the next read failed (zlib's -3). The game skips the NBT it doesn't need when it scans a
     * chunk, so every scan of a saved chunk failed.
     */
    @Replaces
    @InstanceMethod
    public static long skip(InflaterInputStream self, long n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("negative skip length");
        }
        byte[] scratch = new byte[(int) Math.min(n, SKIP_CHUNK)];
        long skipped = 0;
        while (skipped < n) {
            int read = self.read(scratch, 0, (int) Math.min(n - skipped, scratch.length));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }
}
