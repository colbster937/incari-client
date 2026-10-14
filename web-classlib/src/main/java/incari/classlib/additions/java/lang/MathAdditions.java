package incari.classlib.additions.java.lang;

/** {@code java.lang.Math} methods TeaVM lacks. */
public final class MathAdditions {

    private MathAdditions() {}

    /**
     * {@code a * b + c}. Not fused: WebAssembly has no fused multiply-add, so this rounds twice. The
     * callers (JOML) use it for speed, not for the single rounding.
     */
    public static double fma(double a, double b, double c) {
        return a * b + c;
    }

    /** {@code a * b + c}, computed in double, which is exact for the product of two floats. */
    public static float fma(float a, float b, float c) {
        return (float) ((double) a * (double) b + (double) c);
    }

    public static int powExact(int base, int exponent) {
        if (exponent < 0) {
            throw new ArithmeticException("negative exponent");
        }
        int result = 1;
        for (int i = 0; i < exponent; i++) {
            result = Math.multiplyExact(result, base);
        }
        return result;
    }
}
