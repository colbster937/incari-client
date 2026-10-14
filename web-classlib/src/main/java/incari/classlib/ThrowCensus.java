package incari.classlib;

import incari.classlib.concurrent.Waits;
import java.util.Arrays;
import org.teavm.interop.PlatformMarker;
import org.teavm.interop.Platforms;
import org.teavm.jso.JSBody;

/**
 * Counts the program's throws in a build compiled with {@code -Pteavm.throwCensus} (the web build's
 * {@code ThrowCensus} transformer calls {@link #thrown} before every {@code throw}). In the page a
 * throw off the main thread costs about ten times one on it (ADR 0033), so the census counts each
 * site's throws on and off the render thread and, every ten seconds that something throws, logs
 * the busiest sites: {@code [Incari] Throws <off>/<on> at site <n> (<exception>)}, the sites'
 * methods in the build's {@code throw-sites.txt}. Development only. The web build refers to this
 * class by name.
 */
public final class ThrowCensus {

    private static final long INTERVAL_MILLIS = 10_000;
    private static final int SHOWN = 15;

    private static int[] off = new int[0];
    private static int[] on = new int[0];
    private static String[] types = new String[0];
    private static long reportAt;
    private static long totalOff;
    private static long totalOn;
    // A throw while counting one (the report's printing) isn't counted.
    private static boolean counting;

    private ThrowCensus() {}

    /** Before a throw at {@code site}, of {@code exception}. */
    @SuppressWarnings("ReferenceEquality") // threads are compared by identity
    public static void thrown(Object exception, int site) {
        if (counting) {
            return;
        }
        counting = true;
        try {
            count(exception, site);
        } finally {
            counting = false;
        }
    }

    @SuppressWarnings("ReferenceEquality") // threads are compared by identity
    private static void count(Object exception, int site) {
        if (site >= off.length) {
            int length = Math.max(site + 1, off.length * 2);
            off = Arrays.copyOf(off, length);
            on = Arrays.copyOf(on, length);
            types = Arrays.copyOf(types, length);
        }
        if (Thread.currentThread() == Waits.watched()) {
            on[site]++;
            totalOn++;
        } else {
            off[site]++;
            totalOff++;
        }
        if (types[site] == null) {
            types[site] = exception.getClass().getName();
        }
        long now = System.currentTimeMillis();
        if (reportAt == 0) {
            reportAt = now + INTERVAL_MILLIS;
        } else if (now >= reportAt) {
            report();
            reportAt = now + INTERVAL_MILLIS;
        }
    }

    private static void report() {
        StringBuilder line = new StringBuilder(
                        "[Incari] Throws in the last 10 s, off/on the render thread (since start ")
                .append(totalOff)
                .append('/')
                .append(totalOn)
                .append("):");
        boolean[] shown = new boolean[off.length];
        for (int n = 0; n < SHOWN; n++) {
            int best = -1;
            for (int site = 0; site < off.length; site++) {
                if (!shown[site]
                        && (off[site] + on[site]) > 0
                        && (best < 0 || off[site] * 10 + on[site] > off[best] * 10 + on[best])) {
                    best = site;
                }
            }
            if (best < 0) {
                break;
            }
            shown[best] = true;
            line.append("\n[Incari] Throws ")
                    .append(off[best])
                    .append('/')
                    .append(on[best])
                    .append(" at site ")
                    .append(best)
                    .append(" (")
                    .append(types[best])
                    .append(')');
        }
        // To the page's console directly: the game routes System.out through its own logging.
        if (inPage()) {
            log(line.toString());
        } else {
            System.out.println(line);
        }
        Arrays.fill(off, 0);
        Arrays.fill(on, 0);
    }

    /** True in the WebAssembly build; TeaVM replaces calls to it with the constant. */
    @PlatformMarker(Platforms.WEBASSEMBLY_GC)
    private static boolean inPage() {
        return false;
    }

    @JSBody(params = "message", script = "console.log(message);")
    private static native void log(String message);
}
