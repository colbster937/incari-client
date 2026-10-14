package incari.tools.webbuild;

import incari.tools.survey.Finding;
import incari.tools.survey.Origins;
import incari.tools.survey.SurveyRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Decides which missing symbols the browser build removes, and keeps the list of what it removed.
 *
 * <p>The decision is the survey's: {@code tools/survey/rules.txt} files every API the browser lacks
 * under stub, shim, rewrite or delete. References to missing symbols filed under <em>delete</em>
 * (features the browser build drops) or <em>rewrite</em> (code to be replaced) are
 * removed; stub and shim symbols get real code in {@code web-classlib}, so a missing one of those
 * stays a link error. Symbols that exist are never touched, however a rule matches them.
 *
 * <p>How a reference is removed follows the category. A deleted feature is stripped: calls into it
 * vanish and anything it would have produced is null or zero, so the game carries on without it
 * (a profiler, telemetry, Realms). Code awaiting a rewrite throws where it's reached, so unported
 * code fails loudly rather than misbehaving quietly.
 */
final class Removals {

    private final SurveyRules rules;
    private final Origins origins;
    // rule reason -> symbol -> callers
    private final Map<String, Map<String, Map<String, Integer>>> removed = new TreeMap<>();

    Removals(SurveyRules rules, Origins origins) {
        this.rules = rules;
        this.origins = origins;
    }

    /** How a reference goes: it throws where it was (rewrite), or it's stripped (delete). */
    enum Removal {
        THROW,
        STRIP
    }

    /**
     * Whether to remove a reference to a missing symbol, and how; null leaves it (and it fails the
     * link).
     *
     * @param symbol {@code pkg.Class} or {@code pkg.Class#member}
     * @param caller the class holding the reference
     */
    synchronized @Nullable Removal removal(Finding.Kind kind, String symbol, String caller) {
        Finding finding = new Finding(kind, symbol, "", caller, null, -1, null);
        SurveyRules.TriageRule rule = rules.classify(finding, kind.ruleName(), origins.of(caller));
        if (rule == null
                || (rule.category() != SurveyRules.Category.DELETE
                        && rule.category() != SurveyRules.Category.REWRITE)) {
            return null;
        }
        removed.computeIfAbsent(rule.reason(), r -> new TreeMap<>())
                .computeIfAbsent(symbol, s -> new TreeMap<>())
                .merge(origins.appClass(caller), 1, Integer::sum);
        return rule.category() == SurveyRules.Category.DELETE ? Removal.STRIP : Removal.THROW;
    }

    /** How a missing class is filed: a JDK class TeaVM lacks, or one no jar provides. */
    synchronized Finding.Kind missingClassKind(String className) {
        return Origins.HOST_JDK.equals(origins.of(className)) ? Finding.Kind.HOST_JDK : Finding.Kind.MISSING_CLASS;
    }

    synchronized int count() {
        int count = 0;
        for (Map<String, Map<String, Integer>> symbols : removed.values()) {
            for (Map<String, Integer> callers : symbols.values()) {
                for (int n : callers.values()) {
                    count += n;
                }
            }
        }
        return count;
    }

    /** Writes every removed reference, grouped by the rule that removed it. */
    synchronized void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# References the browser build removed: reaching one at run time throws.");
        lines.add("# Grouped by the tools/survey/rules.txt entry (delete or rewrite) that removed them.");
        for (Map.Entry<String, Map<String, Map<String, Integer>>> group : removed.entrySet()) {
            lines.add("");
            lines.add(group.getKey());
            for (Map.Entry<String, Map<String, Integer>> symbol :
                    group.getValue().entrySet()) {
                lines.add("  " + symbol.getKey());
                for (Map.Entry<String, Integer> caller : symbol.getValue().entrySet()) {
                    lines.add("      from " + caller.getKey()
                            + (caller.getValue() > 1 ? " (" + caller.getValue() + ")" : ""));
                }
            }
        }
        Files.write(file, lines);
    }
}
