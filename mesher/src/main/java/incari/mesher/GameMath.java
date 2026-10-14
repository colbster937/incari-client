package incari.mesher;

/**
 * The few pieces of the game's arithmetic a mesh depends on, computed the same way so a mesh comes
 * out the same: the position hash that picks model variants and plant offsets, the game's random
 * number generator (Java's 48-bit linear congruential one), and its table-driven sine and
 * arctangent, which the flow of fluids is drawn with.
 *
 * <p>The table builder checks these against the game itself before any mesh is made
 * ({@code incari.client.render.mesher.MeshTableExport}), so a change in a new game version fails
 * loudly instead of drawing wrong.
 */
public final class GameMath {

    private GameMath() {}

    /** The game's hash of a block position: model variants and plant offsets are drawn from it. */
    public static long positionSeed(int x, int y, int z) {
        // x's product overflows as an int before it's widened, as the game computes it.
        int scaledX = x * 3129871;
        long mixed = scaledX ^ z * 116129781L ^ y;
        mixed = mixed * mixed * 42317861L + mixed * 11L;
        return mixed >> 16;
    }

    /** Java's linear congruential generator, as the game's single-threaded random source runs it. */
    public static final class Random {

        private static final long MULTIPLIER = 0x5DEECE66DL;
        private static final long MASK = (1L << 48) - 1;

        private long state;

        public void setSeed(long seed) {
            state = (seed ^ MULTIPLIER) & MASK;
        }

        int next(int bits) {
            state = state * MULTIPLIER + 11L & MASK;
            return (int) (state >> (48 - bits));
        }

        public int nextInt(int bound) {
            if ((bound & (bound - 1)) == 0) {
                return (int) (((long) bound * next(31)) >> 31);
            }
            int sample;
            int remainder;
            do {
                sample = next(31);
                remainder = sample % bound;
            } while (sample - remainder + (bound - 1) < 0);
            return remainder;
        }

        public long nextLong() {
            int high = next(32);
            int low = next(32);
            return ((long) high << 32) + low;
        }
    }

    // 65536 steps a turn, as the game rounds it.
    private static final double SINE_STEPS_PER_RADIAN = 10430.378350470453;
    private static final float[] SINE = new float[65536];
    // Arctangent: a table of arcsines at 1/256 steps and a cubic correction between them.
    private static final double ARCSINE_BIAS = Double.longBitsToDouble(0x42B0000000000000L);
    private static final double[] ARCSINE = new double[257];
    private static final double[] COSINE_OF_ARCSINE = new double[257];

    static {
        for (int i = 0; i < SINE.length; i++) {
            SINE[i] = (float) Math.sin(i / SINE_STEPS_PER_RADIAN);
        }
        for (int i = 0; i <= 256; i++) {
            double arcsine = Math.asin(i / 256.0);
            ARCSINE[i] = arcsine;
            COSINE_OF_ARCSINE[i] = Math.cos(arcsine);
        }
    }

    /** The game's table sine. */
    public static float sin(double radians) {
        return SINE[(int) ((long) (radians * SINE_STEPS_PER_RADIAN) & 0xFFFFL)];
    }

    /** The game's table cosine: the sine a quarter turn on. */
    public static float cos(double radians) {
        return SINE[(int) ((long) (radians * SINE_STEPS_PER_RADIAN + 16384.0) & 0xFFFFL)];
    }

    /** The game's fast arctangent of {@code y / x}. */
    public static double atan2(double y, double x) {
        double lengthSquared = x * x + y * y;
        if (Double.isNaN(lengthSquared)) {
            return Double.NaN;
        }
        boolean flipY = y < 0.0;
        if (flipY) {
            y = -y;
        }
        boolean flipX = x < 0.0;
        if (flipX) {
            x = -x;
        }
        boolean swapped = y > x;
        if (swapped) {
            double t = x;
            x = y;
            y = t;
        }
        double inverseLength = inverseSqrt(lengthSquared);
        x *= inverseLength;
        y *= inverseLength;
        double biased = ARCSINE_BIAS + y;
        int step = (int) Double.doubleToRawLongBits(biased);
        double nearest = ARCSINE[step];
        double nearestCosine = COSINE_OF_ARCSINE[step];
        double nearestSine = biased - ARCSINE_BIAS;
        double remainder = y * nearestCosine - x * nearestSine;
        double correction = (6.0 + remainder * remainder) * remainder * (1.0 / 6.0);
        double angle = nearest + correction;
        if (swapped) {
            angle = Math.PI / 2 - angle;
        }
        if (flipX) {
            angle = Math.PI - angle;
        }
        if (flipY) {
            angle = -angle;
        }
        return angle;
    }

    /** One Newton step from the bit-trick estimate, as the game's arctangent uses. */
    private static double inverseSqrt(double value) {
        double half = 0.5 * value;
        long bits = 0x5FE6EB50C7B537AAL - (Double.doubleToRawLongBits(value) >> 1);
        double estimate = Double.longBitsToDouble(bits);
        return estimate * (1.5 - half * estimate * estimate);
    }
}
