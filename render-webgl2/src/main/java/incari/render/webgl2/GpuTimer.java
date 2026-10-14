package incari.render.webgl2;

import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * Times each frame's GPU work with EXT_disjoint_timer_query_webgl2, where the browser has
 * it: a query opens at the frame's first command and closes after its blit to the canvas, and its
 * answer is read when the GPU gives it, a frame or two later. Not opening the query until the first
 * command keeps the time the GPU sits idle between frames (the page waiting for vsync) out of it.
 *
 * <p>A few queries are kept in flight; a frame that finds them all still pending isn't timed.
 */
final class GpuTimer {

    private static final int QUERIES = 4;

    private final WebGL2RenderingContext gl;
    private final JSObject @Nullable [] queries;
    private final boolean[] pending = new boolean[QUERIES];
    /** The order the pending queries were issued in, oldest first, as indices into {@link #queries}. */
    private final int[] order = new int[QUERIES];

    private int pendingCount;
    private int open = -1;
    private double lastMillis = Double.NaN;

    GpuTimer(WebGL2RenderingContext gl) {
        this.gl = gl;
        if (WebGl.extension(gl, "EXT_disjoint_timer_query_webgl2") == null) {
            queries = null;
            return;
        }
        queries = new JSObject[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            queries[i] = WebGl.createQuery(gl);
        }
    }

    /** Whether this browser can time the GPU. */
    boolean available() {
        return queries != null;
    }

    /** The GPU's time for the latest frame timed, in milliseconds; NaN if none has been. */
    double lastMillis() {
        return lastMillis;
    }

    /** Called before each command that may reach the GPU: opens the frame's query if it isn't. */
    void frameWork() {
        JSObject[] all = queries;
        // Under ?gpucensus each draw has the one time query WebGL allows open at once.
        if (all == null || open >= 0 || pendingCount == QUERIES || WebGl2Device.timeDraws) {
            return;
        }
        for (int i = 0; i < QUERIES; i++) {
            if (!pending[i]) {
                open = i;
                WebGl.beginTimer(gl, all[i]);
                return;
            }
        }
    }

    /** Called once a frame, after its blit: closes its query and reads any that have finished. */
    void frameEnded() {
        JSObject[] all = queries;
        if (all == null) {
            return;
        }
        if (open >= 0) {
            WebGl.endTimer(gl);
            pending[open] = true;
            order[pendingCount++] = open;
            open = -1;
        }
        // Oldest first: results come in the order the queries were issued.
        while (pendingCount > 0) {
            int oldest = order[0];
            double nanos = WebGl.timerResult(gl, all[oldest]);
            if (nanos == -1) {
                break;
            }
            if (nanos >= 0) {
                lastMillis = nanos / 1_000_000.0;
            }
            pending[oldest] = false;
            System.arraycopy(order, 1, order, 0, --pendingCount);
        }
    }
}
