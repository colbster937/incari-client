package incari.classlib.additions.java.lang;

import java.math.BigInteger;

/** {@code java.lang.Long} methods TeaVM lacks. */
public final class LongAdditions {

    private LongAdditions() {}

    private static final BigInteger LIMIT = BigInteger.ONE.shiftLeft(64);

    public static long parseUnsignedLong(String text, int radix) {
        if (text.startsWith("-")) {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + text + ".");
        }
        BigInteger value = new BigInteger(text, radix);
        if (value.compareTo(LIMIT) >= 0) {
            throw new NumberFormatException("String value " + text + " exceeds range of unsigned long.");
        }
        return value.longValue();
    }
}
