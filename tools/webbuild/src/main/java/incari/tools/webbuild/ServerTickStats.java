package incari.tools.webbuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A singleplayer scenario's server figures, from the server worker's tick table: one row per tick of
 * the columns platform-web's {@code WorkerProfiling} documents. Pure logic, tested on its own.
 *
 * <p>Paused ticks only read the connection, so they're counted apart and left out of the rest. The
 * tick rate is the ticks that ran over the measured seconds: 20 when the server keeps up, less when
 * its ticks (or the page beside it) take more than their 50 ms. Percentiles are nearest rank.
 */
final class ServerTickStats {

    /** The tick table's columns, as the worker writes them. */
    static final int COLUMNS = 4;

    static final int MILLIS = 1;
    static final int PAUSED = 2;
    static final int CHUNKS = 3;

    private final List<double[]> rows = new ArrayList<>();
    private double seconds;

    /** Adds a measured stretch of {@code stretchSeconds}: its ticks, {@code table} flat. */
    void add(double[] table, double stretchSeconds) {
        if (table.length % COLUMNS != 0) {
            throw new IllegalArgumentException("A tick table of " + table.length + " numbers");
        }
        for (int i = 0; i < table.length; i += COLUMNS) {
            rows.add(Arrays.copyOfRange(table, i, i + COLUMNS));
        }
        seconds += stretchSeconds;
    }

    int ticks() {
        return rows.size();
    }

    /** The figures, by name, in a fixed order; a figure nothing measured is {@code NaN}. */
    Map<String, Double> figures() {
        double[] running = rows.stream()
                .filter(row -> row[PAUSED] == 0)
                .mapToDouble(row -> row[MILLIS])
                .sorted()
                .toArray();
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("serverTicksPerSecond", seconds > 0 ? running.length / seconds : Double.NaN);
        out.put(
                "serverTickMillis",
                running.length == 0 ? Double.NaN : Arrays.stream(running).sum() / running.length);
        out.put("serverTickP95Millis", percentile(running, 95));
        out.put("serverTickMaxMillis", running.length == 0 ? Double.NaN : running[running.length - 1]);
        out.put("serverPausedTicks", (double) (rows.size() - running.length));
        out.put("serverChunks", rows.isEmpty() ? Double.NaN : rows.getLast()[CHUNKS]);
        return out;
    }

    private static double percentile(double[] sorted, double percent) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(percent / 100 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }
}
