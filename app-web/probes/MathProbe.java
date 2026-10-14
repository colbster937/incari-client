/**
 * Probe for tools/webbuild's {@code MathIntrinsics}: {@code Math.floor}, {@code ceil} and
 * {@code sqrt} compiled to WebAssembly's own instructions give Java's answers, signed zeros,
 * infinities and NaN included. Prints "probe passed" when every case matches its bits (any NaN
 * matches any NaN).
 */
public final class MathProbe {

    private MathProbe() {}

    private static final double[] INPUTS = {
        0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.0, -2.0, 1e300, -1e300, 4503599627370495.5, -4503599627370495.5,
        Double.MIN_VALUE, -Double.MIN_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 2.0,
        1e-300, 0.1
    };

    // Java's answers for INPUTS, in order: floor, ceil, sqrt.
    private static final double[][] EXPECTED = {
        {0.0, 0.0, 0.0},
        {-0.0, -0.0, -0.0},
        {0.0, 1.0, 0.7071067811865476},
        {-1.0, -0.0, Double.NaN},
        {1.0, 2.0, 1.224744871391589},
        {-2.0, -1.0, Double.NaN},
        {2.0, 2.0, 1.4142135623730951},
        {-2.0, -2.0, Double.NaN},
        {1e300, 1e300, 1e150},
        {-1e300, -1e300, Double.NaN},
        {4503599627370495.0, 4503599627370496.0, 6.710886399999999e7},
        {-4503599627370496.0, -4503599627370495.0, Double.NaN},
        {0.0, 1.0, 2.2227587494850775e-162},
        {-1.0, -0.0, Double.NaN},
        {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
        {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN},
        {Double.NaN, Double.NaN, Double.NaN},
        {2.0, 2.0, 1.4142135623730951},
        {0.0, 1.0, 1e-150},
        {0.0, 1.0, 0.31622776601683794},
    };

    public static void main(String[] args) {
        int failures = 0;
        for (int i = 0; i < INPUTS.length; i++) {
            double x = INPUTS[i];
            double[] got = {Math.floor(x), Math.ceil(x), Math.sqrt(x)};
            for (int k = 0; k < 3; k++) {
                if (!same(got[k], EXPECTED[i][k])) {
                    failures++;
                    System.out.println("probe mismatch: " + new String[] {"floor", "ceil", "sqrt"}[k] + "(" + x
                            + ") = " + got[k] + ", expected " + EXPECTED[i][k]);
                }
            }
        }
        System.out.println(failures == 0 ? "probe passed" : "probe failed: " + failures + " mismatches");
    }

    private static boolean same(double a, double b) {
        return Double.isNaN(a) ? Double.isNaN(b) : Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
}
