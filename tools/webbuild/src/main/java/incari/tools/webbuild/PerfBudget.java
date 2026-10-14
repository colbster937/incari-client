package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The benchmark's budgets (ADR 0025, 0026): {@code app-web/benchmark/budgets.properties},
 * absolute limits a figure must stay under, where the baseline ({@link PerfBaseline}) only limits how
 * much worse a figure may get than last time. Pure logic, tested on its own.
 *
 * <pre>
 * # max.&lt;figure&gt;=&lt;limit&gt;: for every machine. A * in the key stands for one part of it (a scenario).
 * max.*.heapLiveMB=600
 * # &lt;machine&gt;.max.&lt;figure&gt;=&lt;limit&gt;: for one machine (times depend on its speed).
 * desktop.max.load.warmTitleMillis=3000
 * </pre>
 *
 * A machine's own limit replaces the general one for the same figure. Figures a run didn't measure
 * (NaN, or absent) aren't checked.
 */
final class PerfBudget {

    /** One limit: the figures it covers, as a pattern, and the most each may be. */
    record Limit(String key, Pattern figures, double max, boolean machineOnly) {}

    /** One figure against its limit. */
    record Check(String figure, double value, Limit limit) {
        boolean over() {
            return value > limit.max();
        }
    }

    private final List<Limit> general;
    private final Map<String, List<Limit>> byMachine;

    private PerfBudget(List<Limit> general, Map<String, List<Limit>> byMachine) {
        this.general = general;
        this.byMachine = byMachine;
    }

    static PerfBudget parse(List<String> lines, String source) {
        List<Limit> general = new ArrayList<>();
        Map<String, List<Limit>> byMachine = new java.util.TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            int max = line.indexOf("max.");
            if (equals < 0 || max < 0 || max > equals || (max > 0 && line.charAt(max - 1) != '.')) {
                throw new CliException(source + ":" + (i + 1) + ": a budget is '[<machine>.]max.<figure>=<limit>'");
            }
            String key = line.substring(max + "max.".length(), equals).strip();
            double limit;
            try {
                limit = Double.parseDouble(line.substring(equals + 1).strip().replace("_", ""));
            } catch (NumberFormatException e) {
                throw new CliException(source + ":" + (i + 1) + ": not a number: " + line.substring(equals + 1));
            }
            Pattern figures = Pattern.compile(Pattern.quote(key).replace("*", "\\E[^.]+\\Q"));
            if (max == 0) {
                general.add(new Limit(key, figures, limit, false));
            } else {
                byMachine
                        .computeIfAbsent(line.substring(0, max - 1), m -> new ArrayList<>())
                        .add(new Limit(key, figures, limit, true));
            }
        }
        return new PerfBudget(List.copyOf(general), byMachine);
    }

    /** Each of {@code figures} with a limit for {@code machine}, against it. */
    List<Check> check(String machine, Map<String, Double> figures) {
        List<Limit> own = byMachine.getOrDefault(machine, List.of());
        List<Check> checks = new ArrayList<>();
        figures.forEach((figure, value) -> {
            if (value == null || value.isNaN()) {
                return;
            }
            Limit limit = find(own, figure);
            if (limit == null) {
                limit = find(general, figure);
            }
            if (limit != null) {
                checks.add(new Check(figure, value, limit));
            }
        });
        return checks;
    }

    private static @org.jspecify.annotations.Nullable Limit find(List<Limit> limits, String figure) {
        for (Limit limit : limits) {
            if (limit.figures().matcher(figure).matches()) {
                return limit;
            }
        }
        return null;
    }
}
