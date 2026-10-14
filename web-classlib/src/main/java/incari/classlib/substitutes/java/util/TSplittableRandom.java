package incari.classlib.substitutes.java.util;

/**
 * {@code java.util.SplittableRandom}, which TeaVM lacks: the SplitMix64 generator (Steele, Lea and
 * Flood, 2014). The core stream methods; nothing in the game draws streams from it.
 */
public final class TSplittableRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long seed;
    private final long gamma;

    public TSplittableRandom(long seed) {
        this(seed, GOLDEN_GAMMA);
    }

    public TSplittableRandom() {
        this(System.nanoTime() ^ Double.doubleToLongBits(Math.random()), GOLDEN_GAMMA);
    }

    private TSplittableRandom(long seed, long gamma) {
        this.seed = seed;
        this.gamma = gamma;
    }

    public TSplittableRandom split() {
        return new TSplittableRandom(nextLong(), mixGamma(nextSeed()));
    }

    public long nextLong() {
        return mix64(nextSeed());
    }

    public int nextInt() {
        return (int) (mix64(nextSeed()) >>> 32);
    }

    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return (int) Math.floorMod(nextLong(), (long) bound);
    }

    public int nextInt(int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return origin + (int) Math.floorMod(nextLong(), (long) bound - origin);
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public boolean nextBoolean() {
        return nextInt() < 0;
    }

    private long nextSeed() {
        seed += gamma;
        return seed;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long mixGamma(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        z = (z ^ (z >>> 33)) | 1L;
        // A gamma with too few bit transitions makes a weak stream; flip it into a better one.
        return Long.bitCount(z ^ (z >>> 1)) < 24 ? z ^ 0xAAAAAAAAAAAAAAAAL : z;
    }
}
