package incari.classlib.additions.java.lang;

/** {@code java.lang.Integer} methods TeaVM lacks. */
public final class IntegerAdditions {

    private IntegerAdditions() {}

    public static int parseUnsignedInt(String text, int radix) {
        if (text.startsWith("-")) {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + text + ".");
        }
        long value = Long.parseLong(text, radix);
        if ((value & 0xFFFF_FFFF_0000_0000L) != 0) {
            throw new NumberFormatException("String value " + text + " exceeds range of unsigned int.");
        }
        return (int) value;
    }
}
