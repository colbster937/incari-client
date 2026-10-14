package incari.tools.webbuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemSeverity;
import org.teavm.model.CallLocation;

/**
 * TeaVM's link errors, grouped by what's missing. One missing class shows up at every call site;
 * the grouped view says which gaps to close first.
 */
final class ProblemReport {

    /** TeaVM names what's missing in the problem text's first {{c0}} or {{m0}} argument. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[a-z](\\d+)}}");

    /** One missing thing and the places that need it. */
    record Group(String missing, String kind, List<String> sites) {}

    private final List<Group> groups;
    private final List<String> other;

    private ProblemReport(List<Group> groups, List<String> other) {
        this.groups = groups;
        this.other = other;
    }

    static ProblemReport of(List<Problem> problems) {
        Map<String, Group> byMissing = new TreeMap<>();
        List<String> other = new ArrayList<>();
        for (Problem problem : problems) {
            if (problem.getSeverity() != ProblemSeverity.ERROR) {
                continue;
            }
            String text = render(problem);
            String site = site(problem.getLocation());
            Object[] params = problem.getParams();
            if (params != null && params.length > 0 && params[0] != null) {
                String missing = String.valueOf(params[0]);
                String kind = kind(problem.getText());
                byMissing
                        .computeIfAbsent(kind + " " + missing, key -> new Group(missing, kind, new ArrayList<>()))
                        .sites()
                        .add(site);
            } else {
                other.add(text + " at " + site);
            }
        }
        List<Group> groups = new ArrayList<>(byMissing.values());
        groups.sort(Comparator.comparingInt((Group g) -> g.sites().size())
                .reversed()
                .thenComparing(Group::missing));
        return new ProblemReport(groups, other);
    }

    private static String kind(String text) {
        if (text.startsWith("Class")) {
            return "class";
        }
        if (text.startsWith("Method")) {
            return "method";
        }
        if (text.startsWith("Field")) {
            return "field";
        }
        return "other";
    }

    private static String render(Problem problem) {
        Matcher matcher = PLACEHOLDER.matcher(problem.getText());
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            Object[] params = problem.getParams();
            String value = params != null && index < params.length ? String.valueOf(params[index]) : "?";
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String site(CallLocation location) {
        if (location == null || location.getMethod() == null) {
            return "(unknown)";
        }
        return location.getMethod().getClassName() + "#" + location.getMethod().getName();
    }

    String summary(int limit) {
        StringBuilder out = new StringBuilder("Link errors by what's missing (most call sites first):\n");
        for (Group group : groups.subList(0, Math.min(limit, groups.size()))) {
            out.append(String.format(
                    "  %4d  %-6s %s  (e.g. %s)%n",
                    group.sites().size(),
                    group.kind(),
                    group.missing(),
                    group.sites().getFirst()));
        }
        if (groups.size() > limit) {
            out.append("  ... and ").append(groups.size() - limit).append(" more\n");
        }
        if (!other.isEmpty()) {
            out.append("  ").append(other.size()).append(" other errors\n");
        }
        return out.toString();
    }

    void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Group group : groups) {
            lines.add(
                    group.kind() + " " + group.missing() + "  (" + group.sites().size() + " sites)");
            group.sites().stream().distinct().sorted().forEach(site -> lines.add("    " + site));
        }
        other.forEach(line -> lines.add("other: " + line));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
