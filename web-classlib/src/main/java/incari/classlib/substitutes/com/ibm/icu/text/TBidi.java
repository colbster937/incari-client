package incari.classlib.substitutes.com.ibm.icu.text;

/**
 * ICU's {@code Bidi} for the browser build, which leaves ICU out. Minimal: every text is one
 * left-to-right run, so right-to-left languages (Arabic, Hebrew) show in logical order until a
 * real bidi shim over the browser's own text layout replaces this.
 */
public class TBidi {

    public static final byte LTR = 0;
    public static final byte RTL = 1;
    public static final byte LEVEL_DEFAULT_LTR = 0x7E;
    public static final byte LEVEL_DEFAULT_RTL = 0x7F;
    public static final short REORDER_DEFAULT = 0;
    public static final short KEEP_BASE_COMBINING = 1;
    public static final short DO_MIRRORING = 2;
    public static final short INSERT_LRM_FOR_NUMERIC = 4;
    public static final short REMOVE_BIDI_CONTROLS = 8;
    public static final short OUTPUT_REVERSE = 16;

    private final String text;

    public TBidi(String paragraph, int flags) {
        this.text = paragraph;
    }

    public void setReorderingMode(int reorderingMode) {}

    public int countRuns() {
        return 1;
    }

    public TBidiRun getVisualRun(int runIndex) {
        return new TBidiRun(0, text.length(), false);
    }

    public String writeReordered(int options) {
        return text;
    }
}
