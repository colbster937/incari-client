package incari.client.world;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * The first draw of a block's positional random, without making the random. The surface rules ask
 * a positional random factory for a new random at a block and take one number from it: the
 * deepslate and bedrock gradients at every block of their bands, the surface depth at every
 * column. Each is three objects (the random, its generator and its Gaussian source); in the
 * terrain step they were about a quarter of all it allocated (the JVM's allocation samples), and
 * in WebAssembly GC every one is a real allocation. The first number of a Xoroshiro128++ generator
 * is a function of its two seeds, so it's computed here as the generator would.
 *
 * <p>Only for the Xoroshiro factory (the overworld's and every modern dimension's); the legacy one
 * makes its random as before.
 */
public final class PositionalRandoms {

    private static final float FLOAT_UNIT = 5.9604645E-8F;
    private static final double DOUBLE_UNIT = 1.1102230246251565E-16;

    private static boolean enabled;

    private PositionalRandoms() {}

    /** Off unless the page turns it on ({@code ?randoms=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook: {@code factory.at(x, y, z).nextFloat()}. Hot path, doesn't allocate. */
    public static float nextFloatAt(PositionalRandomFactory factory, int x, int y, int z) {
        if (enabled && factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro) {
            return (firstLong(xoroshiro.incariSeedAt(x, y, z), xoroshiro.incariSeedHi()) >>> 40) * FLOAT_UNIT;
        }
        RandomSource random = factory.at(x, y, z);
        return random.nextFloat();
    }

    /** Hook: {@code factory.at(x, y, z).nextDouble()}. Hot path, doesn't allocate. */
    public static double nextDoubleAt(PositionalRandomFactory factory, int x, int y, int z) {
        if (enabled && factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory xoroshiro) {
            return (firstLong(xoroshiro.incariSeedAt(x, y, z), xoroshiro.incariSeedHi()) >>> 11) * DOUBLE_UNIT;
        }
        RandomSource random = factory.at(x, y, z);
        return random.nextDouble();
    }

    /** The first {@code nextLong} of a Xoroshiro128++ generator made with these seeds. */
    static long firstLong(long seedLo, long seedHi) {
        if ((seedLo | seedHi) == 0L) {
            // The generator's stand-in for an all-zero seed.
            seedLo = -7046029254386353131L;
            seedHi = 7640891576956012809L;
        }
        return Long.rotateLeft(seedLo + seedHi, 17) + seedLo;
    }
}
