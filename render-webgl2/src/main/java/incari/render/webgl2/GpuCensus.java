package incari.render.webgl2;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * The page's {@code ?gpucensus} (development only, ADR 0031): the GPU's time for each draw call,
 * summed by pipeline and logged as milliseconds a frame every {@link #FRAMES} frames, so a frame's
 * GPU time can be told apart by what drew it. Each draw is timed with a query of its own
 * (EXT_disjoint_timer_query_webgl2); WebGL has only one time query open at once, so the frame's own
 * timer ({@link GpuTimer}) stands aside meanwhile. Timing every draw adds a little to each, so the
 * shares matter more than the sum.
 *
 * <p>Render thread only.
 */
final class GpuCensus {

    private static final int FRAMES = 600;
    /** Queries in flight at most: a draw past these isn't timed. */
    private static final int MAX_PENDING = 4096;

    private final WebGL2RenderingContext gl;
    private final boolean available;
    private final ArrayDeque<JSObject> free = new ArrayDeque<>();
    private final ArrayDeque<JSObject> pending = new ArrayDeque<>();
    private final ArrayDeque<String> pendingPipelines = new ArrayDeque<>();
    private final Map<String, double[]> nanos = new TreeMap<>();
    private @Nullable JSObject open;
    private int frames;

    GpuCensus(WebGL2RenderingContext gl) {
        this.gl = gl;
        this.available = WebGl.extension(gl, "EXT_disjoint_timer_query_webgl2") != null;
    }

    /** Before a draw's call: starts timing it. */
    void begin() {
        if (!available || open != null || pending.size() >= MAX_PENDING) {
            return;
        }
        JSObject query = free.poll();
        if (query == null) {
            query = WebGl.createQuery(gl);
        }
        WebGl.beginTimer(gl, query);
        open = query;
    }

    /** After a draw's call: its time goes to {@code pipeline} once the GPU gives it. */
    void end(String pipeline) {
        JSObject query = open;
        if (query == null) {
            return;
        }
        WebGl.endTimer(gl);
        open = null;
        pending.add(query);
        pendingPipelines.add(pipeline);
    }

    /** Once a frame: takes the finished results, oldest first, and logs every {@value #FRAMES} frames. */
    void frameEnded() {
        while (true) {
            JSObject query = pending.peek();
            if (query == null) {
                break;
            }
            double result = WebGl.timerResult(gl, query);
            if (result == -1) {
                break;
            }
            pending.poll();
            String pipeline = pendingPipelines.poll();
            free.add(query);
            if (result >= 0 && pipeline != null) {
                nanos.computeIfAbsent(pipeline, name -> new double[1])[0] += result;
            }
        }
        if (++frames == FRAMES) {
            StringBuilder line = new StringBuilder("[Incari] GPU ms a frame by pipeline over ")
                    .append(FRAMES)
                    .append(" frames:");
            nanos.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                    .forEach(entry -> line.append(String.format(
                            Locale.ROOT, " %s=%.3f", entry.getKey(), entry.getValue()[0] / 1e6 / FRAMES)));
            WebGl.info(line.toString());
            nanos.clear();
            frames = 0;
        }
    }
}
