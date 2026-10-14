package incari.tools.webbuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scenario's figures, from the page's frame table: one row per frame of the columns
 * platform-web's {@code Dom.installFrameMonitor} documents. Pure logic, tested on its own.
 *
 * <p>Frame rates come from the time between frames ending, which includes the wait for the display
 * (what a player sees). The 1% low is the frame rate over the slowest 1% of frames (at least one):
 * their mean frame time, so a handful of stutters shows even when the 99th percentile frame is
 * smooth. Percentiles are nearest rank. Means skip frames that didn't measure a column.
 */
final class FrameStats {

    /** The frame table's columns, as the page writes them. */
    static final int COLUMNS = 16;

    static final int INTERVAL = 1;
    static final int TICK = 4;
    static final int EXTRACT = 5;
    static final int RENDER = 6;
    static final int GPU = 7;
    static final int DRAWS = 8;
    static final int TERRAIN_DRAWS = 9;
    static final int TERRAIN_SECTIONS = 10;
    static final int MESH_QUEUE = 11;
    static final int HEAP = 12;
    static final int NATIVE = 13;
    static final int POOL = 14;
    static final int POOL_LATE = 15;

    private final List<double[]> rows = new ArrayList<>();
    private GcStats gc = GcStats.NONE;

    /** Adds a measured stretch's frames: {@code table} flat, {@link #COLUMNS} numbers a frame. */
    void add(double[] table) {
        if (table.length % COLUMNS != 0) {
            throw new IllegalArgumentException("A frame table of " + table.length + " numbers");
        }
        for (int i = 0; i < table.length; i += COLUMNS) {
            rows.add(Arrays.copyOfRange(table, i, i + COLUMNS));
        }
    }

    /** Adds a measured stretch's garbage collections. */
    void add(GcStats stretch) {
        gc = gc.plus(stretch);
    }

    int frames() {
        return rows.size();
    }

    /**
     * The figures, by name, in a fixed order: the names the report and the baseline use. A figure
     * nothing measured is {@code NaN}.
     */
    Map<String, Double> figures() {
        double[] intervals = column(INTERVAL);
        Arrays.sort(intervals);
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("frames", (double) rows.size());
        out.put("fpsMean", intervals.length == 0 ? Double.NaN : intervals.length * 1000 / sum(intervals));
        out.put("fpsMedian", 1000 / percentile(intervals, 50));
        out.put("fpsLow1", 1000 / slowestMean(intervals, 1));
        out.put("frameP99Millis", percentile(intervals, 99));
        out.put("tickMillis", mean(column(TICK)));
        out.put("extractMillis", mean(column(EXTRACT)));
        out.put("renderMillis", mean(column(RENDER)));
        out.put("gpuMillis", mean(column(GPU)));
        out.put("drawCalls", mean(counts(DRAWS)));
        out.put("terrainDrawCalls", mean(counts(TERRAIN_DRAWS)));
        out.put("terrainSections", mean(counts(TERRAIN_SECTIONS)));
        out.put("poolMillis", mean(column(POOL)));
        out.put("meshQueueMean", mean(counts(MESH_QUEUE)));
        out.put("meshQueueMax", max(counts(MESH_QUEUE)));
        out.put("heapMaxMB", max(column(HEAP)));
        out.put("nativeMaxMB", max(column(NATIVE)));
        slowestFrames(out);
        out.put("gcCount", gc.count() < 0 ? Double.NaN : gc.count());
        out.put("gcTotalMillis", gc.count() < 0 ? Double.NaN : gc.totalMillis());
        out.put("gcMaxPauseMillis", gc.count() < 0 ? Double.NaN : gc.maxMillis());
        return out;
    }

    /**
     * What the slowest 1% of frames (by the time since the previous one) were made of, for telling
     * stutter apart: the interval, how much of it the frame itself took (from its animation frame;
     * the rest is the page's work between frames and waiting for the display), and the game's tick,
     * extract and render inside it, and the background pool's work between it and the previous one
     * (and how far past its slot that ran). Recorded, never gated (no baseline rules name them).
     */
    private void slowestFrames(Map<String, Double> out) {
        List<double[]> measured = new ArrayList<>(
                rows.stream().filter(row -> !Double.isNaN(row[INTERVAL])).toList());
        measured.sort((a, b) -> Double.compare(b[INTERVAL], a[INTERVAL]));
        int count = Math.max(1, measured.size() / 100);
        List<double[]> slowest = measured.subList(0, Math.min(count, measured.size()));
        out.put("slow1.intervalMillis", meanOf(slowest, INTERVAL));
        out.put("slow1.frameMillis", meanOf(slowest, 2));
        out.put("slow1.tickMillis", meanOf(slowest, TICK));
        out.put("slow1.extractMillis", meanOf(slowest, EXTRACT));
        out.put("slow1.renderMillis", meanOf(slowest, RENDER));
        out.put("slow1.poolMillis", meanOf(slowest, POOL));
        out.put("slow1.poolLateMillis", meanOf(slowest, POOL_LATE));
    }

    private static double meanOf(List<double[]> rows, int index) {
        return mean(rows.stream()
                .mapToDouble(row -> row[index])
                .filter(v -> !Double.isNaN(v))
                .toArray());
    }

    /** A column's measured values (not {@code NaN}). */
    private double[] column(int index) {
        return rows.stream()
                .mapToDouble(row -> row[index])
                .filter(v -> !Double.isNaN(v))
                .toArray();
    }

    /** A count column's values: -1 means the backend didn't count. */
    private double[] counts(int index) {
        return Arrays.stream(column(index)).filter(v -> v >= 0).toArray();
    }

    /** Nearest rank; {@code sorted} ascending. NaN if empty. */
    static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(percentile / 100 * sorted.length);
        return sorted[Math.max(0, Math.min(rank - 1, sorted.length - 1))];
    }

    /** The mean of the slowest {@code percent}% of {@code sorted} (ascending), at least one; NaN if empty. */
    static double slowestMean(double[] sorted, double percent) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int count = Math.max(1, (int) Math.floor(sorted.length * percent / 100));
        double total = 0;
        for (int i = sorted.length - count; i < sorted.length; i++) {
            total += sorted[i];
        }
        return total / count;
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    private static double mean(double[] values) {
        return values.length == 0 ? Double.NaN : sum(values) / values.length;
    }

    private static double max(double[] values) {
        return values.length == 0 ? Double.NaN : Arrays.stream(values).max().orElseThrow();
    }
}
