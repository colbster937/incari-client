package incari.framework.event;

/**
 * Counts a mouse button's clicks over the last second: clicks a second, as CPS and Keystrokes show
 * them. Fed from {@link Events#CLICK}; read at any time. Allocates nothing.
 */
public final class ClickCounter {

    /** The window counted, in milliseconds. */
    public static final long WINDOW = 1000;

    /** More than anyone clicks in a second: older clicks beyond it are forgotten. */
    private static final int MAX = 64;

    private final long[] times = new long[MAX];
    private int next;
    private int size;

    /** A click at {@code millis}. */
    public void click(long millis) {
        times[next] = millis;
        next = (next + 1) % MAX;
        size = Math.min(size + 1, MAX);
    }

    /** Clicks in the second up to {@code millis}. */
    public int perSecond(long millis) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long at = times[(next - 1 - i + MAX) % MAX];
            if (millis - at >= WINDOW) {
                break;
            }
            count++;
        }
        return count;
    }

    /** Forgets every click. */
    public void clear() {
        size = 0;
        next = 0;
    }
}
