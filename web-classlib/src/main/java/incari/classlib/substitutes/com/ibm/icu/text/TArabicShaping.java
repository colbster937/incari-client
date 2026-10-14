package incari.classlib.substitutes.com.ibm.icu.text;

/**
 * ICU's {@code ArabicShaping} for the browser build: no shaping, the text comes back as given
 * (see {@link TBidi}). The constants are ICU's option bits, which callers combine.
 */
public final class TArabicShaping {

    public static final int LETTERS_SHAPE = 8;
    public static final int LETTERS_UNSHAPE = 16;
    public static final int DIGITS_EN2AN = 32;
    public static final int TEXT_DIRECTION_VISUAL_LTR = 4;
    public static final int LENGTH_GROW_SHRINK = 0;

    public TArabicShaping(int options) {}

    public String shape(String text) throws TArabicShapingException {
        return text;
    }
}
