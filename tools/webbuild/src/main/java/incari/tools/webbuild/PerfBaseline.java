package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The benchmark's baseline: {@code app-web/benchmark/baseline.properties}, the figures each
 * machine last recorded, and the rules that say how much worse a figure may get before the
 * benchmark fails. Pure logic, tested on its own.
 *
 * <pre>
 * # rule.&lt;figure&gt;=&lt;higher|lower&gt; &lt;percent&gt; &lt;absolute&gt;
 * rule.fpsMedian=higher 5 1
 * # &lt;machine&gt;.&lt;scenario or group&gt;.&lt;figure&gt;=&lt;value&gt;
 * desktop.flythrough.fpsMedian=60.0
 * </pre>
 *
 * A figure regresses when it's worse than its baseline by more than the percentage of the baseline
 * and more than the absolute amount (both, so a tiny figure's noise doesn't fail the run). Figures
 * without a rule are recorded but never fail. A figure's rule is found by the last part of its key.
 */
final class PerfBaseline {

    /** Which way is better, and by how much a figure may get worse. */
    record Rule(boolean higherIsBetter, double percent, double absolute) {

        /** How much worse {@code current} is than {@code baseline}, in the figure's units; negative if better. */
        double worsening(double baseline, double current) {
            return higherIsBetter ? baseline - current : current - baseline;
        }

        boolean regressed(double baseline, double current) {
            double worse = worsening(baseline, current);
            return worse > Math.abs(baseline) * percent / 100 && worse > absolute;
        }
    }

    /** One figure against its baseline. */
    record Comparison(String key, double baseline, double current, Rule rule, boolean regressed) {}

    private final List<String> lines;
    private final Map<String, Rule> rules;

    private PerfBaseline(List<String> lines, Map<String, Rule> rules) {
        this.lines = lines;
        this.rules = rules;
    }

    static PerfBaseline parse(List<String> lines, String source) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.startsWith("rule.")) {
                continue;
            }
            int equals = line.indexOf('=');
            String[] words = equals < 0
                    ? new String[0]
                    : line.substring(equals + 1).strip().split("\\s+", -1);
            if (words.length != 3 || !(words[0].equals("higher") || words[0].equals("lower"))) {
                throw new CliException(
                        source + ":" + (i + 1) + ": a rule is 'rule.<figure>=<higher|lower> <percent> <absolute>'");
            }
            rules.put(
                    line.substring("rule.".length(), equals).strip(),
                    new Rule(words[0].equals("higher"), Double.parseDouble(words[1]), Double.parseDouble(words[2])));
        }
        return new PerfBaseline(List.copyOf(lines), rules);
    }

    /** {@code machine}'s recorded figures, by key without the machine. */
    Map<String, Double> figures(String machine) {
        Map<String, Double> out = new TreeMap<>();
        String prefix = machine + ".";
        for (String raw : lines) {
            String line = raw.strip();
            int equals = line.indexOf('=');
            if (line.startsWith("#") || line.startsWith("rule.") || equals < 0 || !line.startsWith(prefix)) {
                continue;
            }
            out.put(
                    line.substring(prefix.length(), equals).strip(),
                    Double.parseDouble(line.substring(equals + 1).strip()));
        }
        return out;
    }

    /** The rule for {@code key}'s figure (its last part), or null if it has none. */
    @Nullable
    Rule rule(String key) {
        return rules.get(key.substring(key.lastIndexOf('.') + 1));
    }

    /**
     * {@code current} against {@code machine}'s baseline: every figure that has both a baseline and
     * a rule and was measured this time.
     */
    List<Comparison> compare(String machine, Map<String, Double> current) {
        List<Comparison> out = new ArrayList<>();
        for (Map.Entry<String, Double> entry : figures(machine).entrySet()) {
            Rule rule = rule(entry.getKey());
            Double now = current.get(entry.getKey());
            if (rule == null || now == null || now.isNaN() || entry.getValue().isNaN()) {
                continue;
            }
            out.add(new Comparison(entry.getKey(), entry.getValue(), now, rule, rule.regressed(entry.getValue(), now)));
        }
        return out;
    }

    /**
     * The file with {@code machine}'s figures replaced by {@code current} (what isn't measured is
     * left out), everything else as it was.
     */
    List<String> withFigures(String machine, Map<String, Double> current) {
        String prefix = machine + ".";
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.strip().startsWith(prefix)) {
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.getLast().isBlank()) {
            out.removeLast();
        }
        out.add("");
        new TreeMap<>(current).forEach((key, value) -> {
            if (!value.isNaN()) {
                out.add(prefix + key + "=" + format(value));
            }
        });
        return out;
    }

    static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }
}
