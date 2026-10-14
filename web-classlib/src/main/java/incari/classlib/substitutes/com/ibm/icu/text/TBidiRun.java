package incari.classlib.substitutes.com.ibm.icu.text;

/** ICU's {@code BidiRun}: one run of a {@link TBidi}. */
public class TBidiRun {

    private final int start;
    private final int length;
    private final boolean odd;

    TBidiRun(int start, int length, boolean odd) {
        this.start = start;
        this.length = length;
        this.odd = odd;
    }

    public int getStart() {
        return start;
    }

    public int getLength() {
        return length;
    }

    public boolean isOddRun() {
        return odd;
    }
}
