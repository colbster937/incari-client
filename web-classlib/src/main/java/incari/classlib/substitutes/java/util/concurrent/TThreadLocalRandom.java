package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import java.util.Random;

/**
 * {@code java.util.concurrent.ThreadLocalRandom}. Replaces TeaVM's, whose constructor fails:
 * {@code Random}'s constructor calls {@code setSeed}, which ThreadLocalRandom must refuse, and
 * TeaVM's refuses it before construction has finished too. One generator serves every thread: in
 * the page, threads are coroutines that never run at the same time. The generator is SplitMix64.
 */
public final class TThreadLocalRandom extends Random {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final long GAMMA = 0x9E3779B97F4A7C15L;
    private static final TThreadLocalRandom INSTANCE = new TThreadLocalRandom();

    private final boolean initialized;
    private long state;

    private TThreadLocalRandom() {
        // Random's constructor calls setSeed; initialized is still false then, so it's allowed.
        super(0L);
        state = Double.doubleToLongBits(Math.random()) ^ System.nanoTime();
        initialized = true;
    }

    public static TThreadLocalRandom current() {
        return INSTANCE;
    }

    @Override
    @SuppressWarnings("UnsynchronizedOverridesSynchronized") // It changes no state to guard.
    public void setSeed(long seed) {
        if (initialized) {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    protected int next(int bits) {
        return (int) (nextLong() >>> (64 - bits));
    }

    @Override
    public long nextLong() {
        state += GAMMA;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    @Override
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    @Override
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    @Override
    public boolean nextBoolean() {
        return nextLong() < 0;
    }
}
