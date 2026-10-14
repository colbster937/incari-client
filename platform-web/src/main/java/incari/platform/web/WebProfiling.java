package incari.platform.web;

import incari.platform.api.profile.FrameSample;
import incari.platform.api.profile.MemoryUsage;
import incari.platform.api.profile.PlatformProfiling;
import java.nio.ByteBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Profiling in the page (ADR 0021): frames go into the frame monitor's table
 * ({@code window.__incariFrames}), where the benchmark runner reads them; memory is the page's
 * JavaScript heap (Chrome's {@code performance.memory}, which WebAssembly GC objects count in) and
 * the module's linear memory, where the game's off-heap buffers are (ADR 0008). The runner asks the
 * game for things through the monitor's {@code requests} ({@link #takeRequest}).
 */
final class WebProfiling implements PlatformProfiling {

    /** A direct buffer, so its view's {@code ArrayBuffer} is the module's linear memory. */
    private final ByteBuffer probe = ByteBuffer.allocateDirect(8);

    @Override
    public MemoryUsage memory() {
        return new MemoryUsage((long) Dom.heapUsed(), (long) Dom.heapLimit(), linearBytes(), (long) Dom.collections());
    }

    /** The module's linear memory. It only grows, and growing detaches older views, so a fresh view each time. */
    private long linearBytes() {
        return (long) Dom.backingBytes(Int8Array.fromJavaBuffer(probe));
    }

    @Override
    public void frameProfiled(FrameSample sample) {
        Dom.frameSample(
                sample.tickMillis(),
                sample.extractMillis(),
                sample.renderMillis(),
                sample.gpuMillis(),
                sample.drawCalls(),
                sample.terrainDrawCalls(),
                sample.terrainSections(),
                sample.meshQueue(),
                (double) linearBytes());
    }

    @Override
    public void mark(String name) {
        Dom.mark(name);
    }

    @Override
    public boolean takeRequest(String name) {
        return takePageRequest(name);
    }

    /** A request the benchmark runner left in the frame monitor's {@code requests}, taken once. */
    @JSBody(
            params = "name",
            script = "var f = window.__incariFrames, r = f && f.requests;"
                    + "if (!r || !r[name]) return false; delete r[name]; return true;")
    private static native boolean takePageRequest(String name);
}
