package incari.platform.web;

import incari.platform.api.profile.FrameSample;
import incari.platform.api.profile.MemoryUsage;
import incari.platform.api.profile.PlatformProfiling;
import org.teavm.jso.JSBody;

/**
 * Profiling in a copy of the game in a worker (ADR 0039): no frames, but singleplayer's server
 * ticks here, and each tick goes into {@code self.__incariServer}, where the singleplayer benchmark
 * reads it through the worker's DevTools target. It keeps the last {@value #ROWS} ticks as rows of
 * {@value #COLUMNS} numbers: when the tick ended (the worker's {@code performance.now()}), how long
 * it took in milliseconds, whether the server was paused, and the overworld's loaded chunks;
 * {@code rows(from)} and {@code count} as the page's frame monitor has them.
 */
final class WorkerProfiling implements PlatformProfiling {

    static final int ROWS = 16384;
    static final int COLUMNS = 4;

    WorkerProfiling() {
        install();
    }

    @JSBody(
            script = "var s = { columns: " + COLUMNS + ", capacity: " + ROWS + ", count: 0 };"
                    + "s.table = new Float64Array(s.capacity * s.columns);"
                    + "s.rows = function(from) { var first = Math.max(from, s.count - s.capacity, 0), out = [];"
                    + "  for (var n = first; n < s.count; n++) { var row = (n % s.capacity) * s.columns;"
                    + "    for (var i = 0; i < s.columns; i++) out.push(s.table[row + i]); } return out; };"
                    + "self.__incariServer = s;")
    private static native void install();

    @Override
    public MemoryUsage memory() {
        // A worker has no performance.memory; the runner reads its heap over DevTools.
        return MemoryUsage.UNKNOWN;
    }

    @Override
    public void frameProfiled(FrameSample sample) {}

    @Override
    public void mark(String name) {}

    @Override
    public void serverTickProfiled(double millis, boolean paused, int loadedChunks) {
        record(millis, paused ? 1 : 0, loadedChunks);
    }

    @JSBody(
            params = {"millis", "paused", "chunks"},
            script = "var s = self.__incariServer; if (!s) return;"
                    + "var row = (s.count % s.capacity) * s.columns, t = s.table;"
                    + "t[row] = performance.now(); t[row + 1] = millis; t[row + 2] = paused; t[row + 3] = chunks;"
                    + "s.count++;")
    private static native void record(double millis, int paused, int chunks);
}
