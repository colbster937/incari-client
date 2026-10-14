package incari.classlib.memory;

import org.teavm.jso.JSBody;

/**
 * The page's JavaScript heap, as {@code Runtime}'s memory figures. WebAssembly GC objects live in
 * that heap, so it is the game's Java heap. Chromium reports it in {@code performance.memory}
 * (coarse and refreshed slowly unless Chrome runs with precise memory info, but real numbers).
 * Other browsers don't; there the figures stay TeaVM's own, {@link Integer#MAX_VALUE} for all
 * three, which reads as an empty heap.
 */
public final class PageHeap {

    /** TeaVM's figure for all three when a browser reports nothing. */
    static final long UNKNOWN = Integer.MAX_VALUE;

    private PageHeap() {}

    /** {@code Runtime.maxMemory()}: the most the heap may grow to. */
    public static long max() {
        return figures(jsHeapSizeLimit(), totalJsHeapSize(), usedJsHeapSize())[0];
    }

    /** {@code Runtime.totalMemory()}: what the heap holds now, used or not. */
    public static long total() {
        return figures(jsHeapSizeLimit(), totalJsHeapSize(), usedJsHeapSize())[1];
    }

    /** {@code Runtime.freeMemory()}: the part of {@link #total()} not in use. */
    public static long free() {
        return figures(jsHeapSizeLimit(), totalJsHeapSize(), usedJsHeapSize())[2];
    }

    /**
     * Max, total and free from what the browser reports (negative for nothing), kept in the order
     * {@code Runtime} promises and callers divide by: {@code 0 <= free <= total <= max}, max > 0.
     */
    static long[] figures(double limit, double total, double used) {
        if (!(limit > 0) || !(total > 0) || !(used >= 0)) {
            return new long[] {UNKNOWN, UNKNOWN, UNKNOWN};
        }
        long t = (long) total;
        long m = Math.max((long) limit, t);
        long f = Math.max(0, t - Math.min((long) used, t));
        return new long[] {m, t, f};
    }

    // Each reads one figure, or -1 where the browser has none (not Chromium, or a worker without it).
    @JSBody(
            script = "var m = self.performance && self.performance.memory;"
                    + " return m && m.jsHeapSizeLimit > 0 ? m.jsHeapSizeLimit : -1;")
    private static native double jsHeapSizeLimit();

    @JSBody(
            script = "var m = self.performance && self.performance.memory;"
                    + " return m && m.totalJSHeapSize > 0 ? m.totalJSHeapSize : -1;")
    private static native double totalJsHeapSize();

    @JSBody(
            script = "var m = self.performance && self.performance.memory;"
                    + " return m && m.usedJSHeapSize >= 0 ? m.usedJSHeapSize : -1;")
    private static native double usedJsHeapSize();
}
