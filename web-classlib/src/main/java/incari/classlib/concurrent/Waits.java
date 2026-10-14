package incari.classlib.concurrent;

import org.jspecify.annotations.Nullable;
import org.teavm.interop.PlatformMarker;
import org.teavm.interop.Platforms;
import org.teavm.jso.JSBody;

/**
 * Where the browser build's waits go. The web build's {@code MonitorElision} routes every
 * {@code Object.wait} in the program here, as it routes notifications to {@link Wakeups}. On the
 * render thread a wait holds back the game's frame (the page goes on, the game doesn't), so its
 * length is added to the page's frame monitor ({@code window.__incariFrames.waitMillis}, platform-web),
 * and a wait of 50 ms or more prints where it was: every blocking call in the class library ends in
 * {@code wait} (the threading audit, ADR 0015). Other threads wait as they would anyway. The web build
 * refers to this class by name.
 */
public final class Waits {

    /** A wait this long holds back the page's frame visibly: the budget for a stall. */
    static final double REPORT_MILLIS = 50;

    private static @Nullable Thread watched;
    private static @Nullable Runnable beforeWait;

    private Waits() {}

    /** The render thread, whose waits are timed; the entry point names it before the game starts. */
    public static void watch(Thread thread) {
        watched = thread;
    }

    /**
     * Runs just before every wait off the render thread. The page's background pool gives itself up
     * there when one of its tasks blocks (platform-web's {@code IdleTimeExecutor}): the work it
     * waits for may be queued behind it.
     */
    public static void beforeWait(Runnable hook) {
        beforeWait = hook;
    }

    /** The render thread, or null before the entry point names it ({@code ThrowCensus} reads it). */
    public static @Nullable Thread watched() {
        return watched;
    }

    public static void wait(Object monitor) throws InterruptedException {
        wait(monitor, 0L);
    }

    public static void wait(Object monitor, long timeout, int nanos) throws InterruptedException {
        wait(monitor, nanos > 0 && timeout < Long.MAX_VALUE ? timeout + 1 : timeout);
    }

    // The caller loops on its own condition; and threads are compared by identity, which is what
    // Thread.equals does too, without a virtual call.
    @SuppressWarnings({"WaitNotInLoop", "ReferenceEquality"})
    public static void wait(Object monitor, long timeout) throws InterruptedException {
        if (Thread.currentThread() != watched) {
            Runnable hook = beforeWait;
            if (hook != null) {
                hook.run();
            }
            monitor.wait(timeout);
            return;
        }
        long start = System.nanoTime();
        try {
            monitor.wait(timeout);
        } finally {
            waited((System.nanoTime() - start) / 1_000_000.0);
        }
    }

    /** Reports a render-thread wait of {@code millis}; package-private for tests. */
    static void waited(double millis) {
        if (inPage()) {
            addToFrame(millis);
        }
        if (millis >= REPORT_MILLIS) {
            String message = "[Incari] The render thread waited " + Math.round(millis) + " ms";
            if (inPage()) {
                // The browser's own stack: a release build's Java stack has no names, but its
                // module's frames do when it keeps them (-Pteavm.names).
                warnWithStack(message);
            } else {
                new Throwable(message).printStackTrace();
            }
        }
    }

    /**
     * True in the WebAssembly build, false on a JVM (where the tests run). TeaVM replaces calls to
     * it with the constant.
     */
    @PlatformMarker(Platforms.WEBASSEMBLY_GC)
    private static boolean inPage() {
        return false;
    }

    @JSBody(
            params = "message",
            script = "var limit = Error.stackTraceLimit; Error.stackTraceLimit = 200;"
                    + " console.warn(message + '\\n' + new Error().stack); Error.stackTraceLimit = limit;")
    private static native void warnWithStack(String message);

    @JSBody(params = "millis", script = "var f = window.__incariFrames; if (f) f.waitMillis += millis;")
    private static native void addToFrame(double millis);
}
