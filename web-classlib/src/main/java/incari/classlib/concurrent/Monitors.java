package incari.classlib.concurrent;

import org.jspecify.annotations.Nullable;

/**
 * Helpers for the {@code java.util.concurrent} substitutes, which all block on plain monitors.
 *
 * <p>It lives outside {@code incari.classlib.substitutes} because every top-level {@code T}-class
 * there is taken for a JDK substitute.
 */
public final class Monitors {

    private Monitors() {}

    /**
     * Waits on {@code monitor}, which the caller holds, for at most {@code nanos} and returns the time
     * left (zero or less once it has run out). Wakes early on a notify or spuriously, so callers loop.
     *
     * <p>Object.wait only takes milliseconds in the browser, so the wait is rounded up: rounding down
     * would turn a sub-millisecond wait into {@code wait(0)}, which never times out.
     */
    @SuppressWarnings("WaitNotInLoop") // callers loop on their own condition
    public static long awaitNanos(Object monitor, long nanos) throws InterruptedException {
        if (nanos <= 0) {
            return nanos;
        }
        long start = System.nanoTime();
        long millis = nanos / 1_000_000L;
        if (millis * 1_000_000L < nanos) {
            millis++;
        }
        monitor.wait(millis);
        return nanos - (System.nanoTime() - start);
    }

    /** Throws {@link InterruptedException} (clearing the flag) if the current thread is interrupted. */
    public static void checkInterrupt() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    /**
     * An unchecked cast that may produce {@code null}, for results stored as {@code Object}.
     *
     * <p>The callers only pass values they stored as a {@code T}, or {@code null} where {@code T}
     * admits it; the compiler can't see that through the {@code Object} field.
     */
    // See above: the value was stored as a T, and inferring T at the call site is the point.
    @SuppressWarnings({"unchecked", "NullAway", "TypeParameterUnusedInFormals"})
    public static <T extends @Nullable Object> T cast(@Nullable Object value) {
        return (T) value;
    }

    /** Rethrows an unchecked throwable as is and wraps a checked one, for code that can't declare it. */
    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new RuntimeException(t);
    }
}
