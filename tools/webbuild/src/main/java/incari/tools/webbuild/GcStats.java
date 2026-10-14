package incari.tools.webbuild;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Garbage collection pauses on the page's main thread, from a DevTools trace of the
 * {@code v8} category: its {@code MinorGC} and {@code MajorGC} events, each a pause of the thread
 * that runs the game. WebAssembly GC objects are collected by the same collector as JavaScript's.
 *
 * @param count pauses; -1 where nothing traced them (a browser without DevTools)
 */
record GcStats(int count, double totalMillis, double maxMillis) {

    /** Nothing traced. */
    static final GcStats NONE = new GcStats(-1, 0, 0);

    /** The trace category the pauses are in. */
    static final String CATEGORY = "v8";

    /**
     * V8's detailed collector events (--gc-trace, ADR 0027): a collection's phases, and the
     * incremental marking steps and finalization that come before a major one.
     */
    static final String DETAIL_CATEGORY = "disabled-by-default-v8.gc";

    static GcStats of(List<JsonObject> events) {
        Set<String> mainThreads = mainThreads(events);
        int count = 0;
        double total = 0;
        double max = 0;
        for (JsonObject event : events) {
            String name = string(event, "name");
            if (!("MinorGC".equals(name) || "MajorGC".equals(name)) || !"X".equals(string(event, "ph"))) {
                continue;
            }
            if (!mainThreads.isEmpty() && !mainThreads.contains(string(event, "pid") + "/" + string(event, "tid"))) {
                continue;
            }
            JsonElement duration = event.get("dur");
            double millis = duration == null ? 0 : duration.getAsDouble() / 1000;
            count++;
            total += millis;
            max = Math.max(max, millis);
        }
        return new GcStats(count, total, max);
    }

    /** The renderer's main thread, by the trace's metadata; empty (every thread) if it has none. */
    private static Set<String> mainThreads(List<JsonObject> events) {
        Set<String> mainThreads = new HashSet<>();
        for (JsonObject event : events) {
            if ("thread_name".equals(string(event, "name")) && "M".equals(string(event, "ph"))) {
                JsonElement args = event.get("args");
                if (args != null
                        && args.isJsonObject()
                        && "CrRendererMain".equals(string(args.getAsJsonObject(), "name"))) {
                    mainThreads.add(string(event, "pid") + "/" + string(event, "tid"));
                }
            }
        }
        return mainThreads;
    }

    /**
     * The main thread's collector events in time order (--gc-trace): each one's start from the
     * first event, its name, its length and its arguments; then each name's count, total and
     * longest. A long pause shows what ran in it and what came before it.
     */
    static String timeline(List<JsonObject> events) {
        Set<String> mainThreads = mainThreads(events);
        List<JsonObject> gc = new ArrayList<>();
        for (JsonObject event : events) {
            String name = string(event, "name");
            boolean collector = name.contains("GC")
                    || name.contains("Marking")
                    || name.contains("Scavenge")
                    || name.contains("Sweep")
                    || name.contains("MC_")
                    || name.contains("MinorMS");
            if (!collector || !"X".equals(string(event, "ph"))) {
                continue;
            }
            if (!mainThreads.isEmpty() && !mainThreads.contains(string(event, "pid") + "/" + string(event, "tid"))) {
                continue;
            }
            gc.add(event);
        }
        gc.sort(Comparator.comparingDouble(event -> event.get("ts").getAsDouble()));
        StringBuilder out = new StringBuilder();
        double start = gc.isEmpty() ? 0 : gc.get(0).get("ts").getAsDouble();
        Map<String, double[]> byName = new TreeMap<>();
        for (JsonObject event : gc) {
            double at = (event.get("ts").getAsDouble() - start) / 1000;
            JsonElement duration = event.get("dur");
            double millis = duration == null ? 0 : duration.getAsDouble() / 1000;
            String name = string(event, "name");
            JsonElement args = event.get("args");
            out.append(String.format(
                    Locale.ROOT,
                    "%10.1f ms  %8.2f ms  %s %s%n",
                    at,
                    millis,
                    name,
                    args == null || args.toString().equals("{}") ? "" : args.toString()));
            double[] summary = byName.computeIfAbsent(name, n -> new double[3]);
            summary[0]++;
            summary[1] += millis;
            summary[2] = Math.max(summary[2], millis);
        }
        out.append(String.format(Locale.ROOT, "%n%-48s %6s %10s %10s%n", "event", "count", "total ms", "max ms"));
        for (Map.Entry<String, double[]> entry : byName.entrySet()) {
            double[] summary = entry.getValue();
            out.append(String.format(
                    Locale.ROOT,
                    "%-48s %6d %10.2f %10.2f%n",
                    entry.getKey(),
                    (int) summary[0],
                    summary[1],
                    summary[2]));
        }
        return out.toString();
    }

    /** Both stretches together. */
    GcStats plus(GcStats other) {
        if (count < 0) {
            return other;
        }
        if (other.count < 0) {
            return this;
        }
        return new GcStats(count + other.count, totalMillis + other.totalMillis, Math.max(maxMillis, other.maxMillis));
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
