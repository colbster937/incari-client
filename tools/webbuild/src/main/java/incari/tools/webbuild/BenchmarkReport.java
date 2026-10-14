package incari.tools.webbuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The benchmark's report as Markdown: a table of each scenario's figures, the load times
 * and download size, the figures against the machine's baseline, and a trend of the frame rates
 * over the runs this machine kept (GitHub renders the Mermaid chart in a job summary, which is
 * where CI puts the report). Pure text, from figures keyed as the runner keys them.
 */
final class BenchmarkReport {

    /** Runs the trend charts show. */
    private static final int TREND_RUNS = 20;

    private record Row(String label, Function<Map<String, Double>, String> cell) {}

    private static final List<Row> ROWS = List.of(
            new Row("Frame rate, median (fps)", f -> number(f.get("fpsMedian"))),
            new Row("1% low (fps)", f -> number(f.get("fpsLow1"))),
            new Row("Frame time, 99th percentile (ms)", f -> number(f.get("frameP99Millis"))),
            new Row("CPU: tick (ms)", f -> number(f.get("tickMillis"))),
            new Row("CPU: extract (ms)", f -> number(f.get("extractMillis"))),
            new Row("CPU: render submit (ms)", f -> number(f.get("renderMillis"))),
            new Row("GPU (ms)", f -> number(f.get("gpuMillis"))),
            new Row("Draw calls", f -> number(f.get("drawCalls"))),
            new Row("Terrain draw calls", f -> number(f.get("terrainDrawCalls"))),
            new Row("Terrain sections drawn", f -> number(f.get("terrainSections"))),
            new Row(
                    "Mesh queue, mean / max",
                    f -> number(f.get("meshQueueMean")) + " / " + number(f.get("meshQueueMax"))),
            new Row("JavaScript heap, max (MB)", f -> number(f.get("heapMaxMB"))),
            new Row("JavaScript heap, live after a collection (MB)", f -> number(f.get("heapLiveMB"))),
            new Row("Linear memory, max (MB)", f -> number(f.get("nativeMaxMB"))),
            new Row("Linear memory, in use after (MB)", f -> number(f.get("nativeLiveMB"))),
            new Row(
                    "GC pauses: count, longest (ms)",
                    f -> number(f.get("gcCount")) + ", " + number(f.get("gcMaxPauseMillis"))),
            new Row("Frames measured", f -> number(f.get("frames"))));

    /** Singleplayer's server worker (ADR 0039), from its tick table and heap. */
    private static final List<Row> SERVER_ROWS = List.of(
            new Row("Server: ticks a second (of 20)", f -> number(f.get("serverTicksPerSecond"))),
            new Row(
                    "Server: tick, mean / 95th percentile / longest (ms)",
                    f -> number(f.get("serverTickMillis")) + " / " + number(f.get("serverTickP95Millis")) + " / "
                            + number(f.get("serverTickMaxMillis"))),
            new Row("Server: loaded chunks at the end", f -> number(f.get("serverChunks"))),
            new Row("Server worker's heap, live after a collection (MB)", f -> number(f.get("workerHeapLiveMB"))));

    private BenchmarkReport() {}

    static String markdown(
            Map<String, String> about,
            Map<String, Double> figures,
            List<PerfBudget.Check> budgets,
            List<PerfBaseline.Comparison> comparisons,
            Map<String, Double> baseline,
            List<Map<String, Double>> history) {
        StringBuilder out = new StringBuilder();
        out.append("## Incari benchmark: ").append(about.get("machine")).append("\n\n");
        String commit = about.getOrDefault("commit", "unknown");
        out.append(about.getOrDefault("renderer", "unknown GPU"))
                .append(", ")
                .append(about.getOrDefault("browser", "unknown browser"))
                .append(", commit `")
                .append(commit.length() > 12 ? commit.substring(0, 12) : commit)
                .append('`');
        String query = about.getOrDefault("query", "");
        if (!query.isEmpty()) {
            out.append(", page options `").append(query).append('`');
        }
        out.append(".\n\n");

        List<String> scenarios = scenarios(figures);
        out.append("| |");
        scenarios.forEach(name -> out.append(' ').append(name).append(" |"));
        out.append("\n|---|");
        scenarios.forEach(name -> out.append("---:|"));
        out.append('\n');
        boolean singleplayer = "singleplayer".equals(about.get("mode"));
        List<Row> rows = new ArrayList<>(ROWS);
        if (singleplayer) {
            rows.addAll(SERVER_ROWS);
        }
        for (Row row : rows) {
            out.append("| ").append(row.label()).append(" |");
            for (String scenario : scenarios) {
                out.append(' ')
                        .append(row.cell().apply(scenarioFigures(figures, scenario)))
                        .append(" |");
            }
            out.append('\n');
        }
        out.append('\n');

        if (singleplayer) {
            out.append("Load: cold start to the title screen ")
                    .append(seconds(figures.get("load.coldTitleMillis")))
                    .append(", creating the world from there ")
                    .append(seconds(figures.get("load.createMillis")))
                    .append(", Save and Quit to the title screen with the world stored ")
                    .append(seconds(figures.get("load.saveQuitMillis")))
                    .append(", opening it again from a reload ")
                    .append(seconds(figures.get("load.openMillis")))
                    .append(". At the title screen after leaving, after a collection: JavaScript heap ")
                    .append(number(figures.get("load.leftHeapLiveMB")))
                    .append(" MB, linear memory in use ")
                    .append(number(figures.get("load.leftNativeLiveMB")))
                    .append(" MB.");
        } else {
            serverLoads(out, figures);
        }
        Double download = figures.get("download.gzipBytes");
        if (download != null && !download.isNaN()) {
            out.append(" Download: ")
                    .append(String.format(Locale.ROOT, "%.1f", download / 1_000_000))
                    .append(" MB gzip.");
        }
        out.append("\n\n");

        if (!budgets.isEmpty()) {
            long over = budgets.stream().filter(PerfBudget.Check::over).count();
            out.append("### Budgets\n\n")
                    .append(over == 0 ? "Every figure is within its budget" : over + " figures are over their budgets")
                    .append(" (`app-web/benchmark/budgets.properties`).\n\n")
                    .append("| Figure | Now | Budget | |\n|---|---:|---:|---|\n");
            for (PerfBudget.Check check : budgets) {
                out.append("| ")
                        .append(check.figure())
                        .append(" | ")
                        .append(number(check.value()))
                        .append(" | ")
                        .append(number(check.limit().max()))
                        .append(check.limit().machineOnly() ? " (this machine)" : "")
                        .append(" | ")
                        .append(check.over() ? "**over**" : "within")
                        .append(" |\n");
            }
            out.append('\n');
        }

        out.append("### Against the baseline\n\n");
        if (baseline.isEmpty()) {
            out.append("No baseline recorded for this machine yet.\n\n");
        } else {
            List<PerfBaseline.Comparison> shown = comparisons.stream()
                    .filter(c -> c.regressed()
                            || Math.abs(c.rule().worsening(c.baseline(), c.current()))
                                    > Math.abs(c.baseline()) * c.rule().percent() / 200)
                    .toList();
            long regressed = comparisons.stream()
                    .filter(PerfBaseline.Comparison::regressed)
                    .count();
            out.append(
                            regressed == 0
                                    ? "No figure regressed past its rule"
                                    : regressed + " figures regressed past their rules")
                    .append(" (")
                    .append(comparisons.size())
                    .append(" compared).");
            if (shown.isEmpty()) {
                out.append(" Nothing moved by more than half its allowance.\n\n");
            } else {
                out.append(" The ones that moved by more than half their allowance:\n\n");
                out.append("| Figure | Baseline | Now | Change | |\n|---|---:|---:|---:|---|\n");
                for (PerfBaseline.Comparison c : shown) {
                    double change = c.baseline() == 0
                            ? Double.NaN
                            : (c.current() - c.baseline()) / Math.abs(c.baseline()) * 100;
                    out.append("| ")
                            .append(c.key())
                            .append(" | ")
                            .append(number(c.baseline()))
                            .append(" | ")
                            .append(number(c.current()))
                            .append(" | ")
                            .append(Double.isNaN(change) ? "n/a" : String.format(Locale.ROOT, "%+.1f%%", change))
                            .append(" | ")
                            .append(
                                    c.regressed()
                                            ? "**regressed**"
                                            : c.rule().worsening(c.baseline(), c.current()) < 0
                                                    ? "better"
                                                    : "worse, within the rule")
                            .append(" |\n");
                }
                out.append('\n');
            }
        }

        if (history.size() >= 2) {
            List<Map<String, Double>> runs = history.subList(Math.max(0, history.size() - TREND_RUNS), history.size());
            out.append("### Trend (this machine's last ").append(runs.size()).append(" runs)\n\n");
            for (String scenario : scenarios) {
                out.append("```mermaid\nxychart-beta\n    title \"")
                        .append(scenario)
                        .append(": median and 1% low frame rate\"\n    x-axis [");
                for (int i = 1; i <= runs.size(); i++) {
                    out.append(i == 1 ? "" : ", ").append(i);
                }
                out.append("]\n    y-axis \"fps\"\n");
                for (String figure : List.of("fpsMedian", "fpsLow1")) {
                    out.append("    line [");
                    for (int i = 0; i < runs.size(); i++) {
                        Double value = runs.get(i).get(scenario + "." + figure);
                        out.append(i == 0 ? "" : ", ").append(value == null || value.isNaN() ? "0" : number(value));
                    }
                    out.append("]\n");
                }
                out.append("```\n\n");
            }
        }
        return out.toString();
    }

    /** The server benchmark's loads: start-ups, the join and the rejoin, and the title screen's memory. */
    private static void serverLoads(StringBuilder out, Map<String, Double> figures) {
        out.append("Load: cold start to the title screen ")
                .append(seconds(figures.get("load.coldTitleMillis")))
                .append(" (first frame ")
                .append(seconds(figures.get("load.coldFirstFrameMillis")))
                .append("), warm start ")
                .append(seconds(figures.get("load.warmTitleMillis")))
                .append(" (first frame ")
                .append(seconds(figures.get("load.warmFirstFrameMillis")))
                .append(", resources loaded ")
                .append(seconds(figures.get("load.warmReloadedMillis")))
                .append(')')
                .append(", joining the server ")
                .append(seconds(figures.get("load.joinMillis")))
                .append(" (again, from a reload: ")
                .append(seconds(figures.get("load.rejoinMillis")))
                .append(')')
                .append(". At the title screen, after a collection: JavaScript heap ")
                .append(number(figures.get("load.titleHeapLiveMB")))
                .append(" MB, linear memory in use ")
                .append(number(figures.get("load.titleNativeLiveMB")))
                .append(" MB.");
    }

    /** The scenarios in the figures, in the order they ran. */
    private static List<String> scenarios(Map<String, Double> figures) {
        Set<String> names = new LinkedHashSet<>();
        for (String key : figures.keySet()) {
            int dot = key.indexOf('.');
            String group = key.substring(0, dot);
            if (!group.equals("load") && !group.equals("download")) {
                names.add(group);
            }
        }
        return new ArrayList<>(names);
    }

    private static Map<String, Double> scenarioFigures(Map<String, Double> figures, String scenario) {
        Map<String, Double> out = new HashMap<>();
        String prefix = scenario + ".";
        figures.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                out.put(key.substring(prefix.length()), value);
            }
        });
        return out;
    }

    private static String number(@Nullable Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "n/a";
        }
        return Math.abs(value) >= 100 || value == Math.rint(value)
                ? Long.toString(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String seconds(@Nullable Double millis) {
        return millis == null || millis.isNaN() ? "n/a" : String.format(Locale.ROOT, "%.1f s", millis / 1000);
    }
}
