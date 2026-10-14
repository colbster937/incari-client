package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The browser build's download-size budget ({@code app-web/download-budget.properties}), and the
 * verdict on a set of measured files. Every file the check sees must be classified, so a new file
 * in the build output fails the check until someone decides whether players download it.
 *
 * <pre>
 * file.&lt;name or glob&gt;=download|debug|report|vendor
 * max.&lt;raw|gzip|brotli&gt;.&lt;file name or "total"&gt;=&lt;bytes&gt;
 * </pre>
 */
record DownloadBudget(Map<String, Kind> classes, List<Limit> limits) {

    /** The name a limit uses for the sum over every {@link Kind#DOWNLOAD} file. */
    static final String TOTAL = "total";

    /** What a file is to the player. */
    enum Kind {
        /** The page fetches it at start: measured and budgeted. */
        DOWNLOAD,
        /** Only a development build has it (TeaVM's stack deobfuscator): measured, not budgeted. */
        DEBUG,
        /** A compile report; the page never fetches it. */
        REPORT,
        /** Mojang's file, which the page fetches but Incari doesn't ship: measured, not budgeted. */
        VENDOR;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a size is measured. */
    enum Measure {
        RAW,
        GZIP,
        BROTLI;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A ceiling on one file's size, or on the {@link #TOTAL}. */
    record Limit(Measure measure, String file, long bytes) {}

    /** One file as the check measured it; {@code brotli} is null when brotli isn't available. */
    record Measured(
            String name,
            Kind kind,
            long raw,
            long gzip,
            @Nullable Long brotli) {

        long size(Measure measure) {
            return switch (measure) {
                case RAW -> raw;
                case GZIP -> gzip;
                case BROTLI -> brotli == null ? -1 : brotli;
            };
        }
    }

    /** A limit and the size it was held against ({@code -1} when it couldn't be measured). */
    record Outcome(Limit limit, long actual) {

        boolean over() {
            return actual > limit.bytes();
        }
    }

    /** The check's result: every limit's outcome, and problems that fail the check. */
    record Verdict(List<Outcome> outcomes, List<String> failures, List<String> notes) {

        boolean passed() {
            return failures.isEmpty();
        }
    }

    static DownloadBudget load(Path file) throws IOException {
        return parse(Files.readAllLines(file), file.toString());
    }

    static DownloadBudget parse(List<String> lines, String source) {
        Map<String, Kind> classes = new LinkedHashMap<>();
        List<Limit> limits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = source + ":" + (i + 1);
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new CliException(where + ": expected key=value, found '" + line + "'");
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.startsWith("file.")) {
                String name = key.substring("file.".length());
                if (classes.put(name, kind(value, where)) != null) {
                    throw new CliException(where + ": " + name + " is classified twice");
                }
            } else if (key.startsWith("max.")) {
                String rest = key.substring("max.".length());
                int dot = rest.indexOf('.');
                if (dot < 0) {
                    throw new CliException(where + ": expected max.<raw|gzip|brotli>.<file>, found '" + key + "'");
                }
                Measure measure = measure(rest.substring(0, dot), where);
                String name = rest.substring(dot + 1);
                limits.add(new Limit(measure, name, bytes(value, where)));
            } else {
                throw new CliException(where + ": unknown key '" + key + "' (expected file.* or max.*)");
            }
        }
        DownloadBudget budget = new DownloadBudget(Map.copyOf(classes), List.copyOf(limits));
        for (Limit limit : limits) {
            if (limit.file().equals(TOTAL)) {
                continue;
            }
            if (isGlob(limit.file())) {
                throw new CliException(source + ": a limit names one file, not a glob: " + limit.file());
            }
            if (budget.kindOf(limit.file()) != Kind.DOWNLOAD) {
                throw new CliException(source + ": " + limit.file()
                        + " has a limit but isn't classified 'download'; only what players download is budgeted");
            }
        }
        return budget;
    }

    /**
     * The file's class: an exact entry first, else the one glob that matches ({@code *} matches
     * within one path segment). Null when nothing classifies it.
     */
    @Nullable
    Kind kindOf(String name) {
        Kind exact = classes.get(name);
        if (exact != null) {
            return exact;
        }
        Kind found = null;
        String foundGlob = null;
        for (Map.Entry<String, Kind> entry : classes.entrySet()) {
            if (isGlob(entry.getKey()) && glob(entry.getKey()).matcher(name).matches()) {
                if (found != null && found != entry.getValue()) {
                    throw new CliException(name + " matches both " + foundGlob + " and " + entry.getKey()
                            + ", which classify it differently");
                }
                found = entry.getValue();
                foundGlob = entry.getKey();
            }
        }
        return found;
    }

    /**
     * Holds the measured files against the limits.
     *
     * @param optional files that may not have been built yet (extra files outside the build
     *     directory, such as the shader manifest): their limits are skipped with a note
     */
    Verdict judge(List<Measured> files, Set<String> optional) {
        Map<String, Measured> byName = new LinkedHashMap<>();
        for (Measured file : files) {
            byName.put(file.name(), file);
        }
        List<Outcome> outcomes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean brotliMissing = files.stream().anyMatch(f -> f.brotli() == null);
        for (Limit limit : limits) {
            long actual;
            if (limit.file().equals(TOTAL)) {
                actual = total(files, limit.measure());
            } else {
                Measured file = byName.get(limit.file());
                if (file == null) {
                    if (optional.contains(limit.file())) {
                        notes.add(limit.file() + " isn't built yet; its "
                                + limit.measure().label() + " limit wasn't checked");
                    } else {
                        failures.add(limit.file() + " has a limit but isn't in the build output");
                    }
                    continue;
                }
                actual = file.size(limit.measure());
            }
            if (limit.measure() == Measure.BROTLI && brotliMissing) {
                notes.add("brotli isn't available, so max.brotli." + limit.file() + " wasn't checked");
                continue;
            }
            Outcome outcome = new Outcome(limit, actual);
            outcomes.add(outcome);
            if (outcome.over()) {
                failures.add("max." + limit.measure().label() + "." + limit.file() + ": " + actual
                        + " bytes, the budget is " + limit.bytes());
            }
        }
        return new Verdict(List.copyOf(outcomes), List.copyOf(failures), List.copyOf(notes));
    }

    /** The sum over every {@link Kind#DOWNLOAD} file; {@code -1} for brotli when it wasn't measured. */
    static long total(List<Measured> files, Measure measure) {
        long sum = 0;
        for (Measured file : files) {
            if (file.kind() == Kind.DOWNLOAD) {
                long size = file.size(measure);
                if (size < 0) {
                    return -1;
                }
                sum += size;
            }
        }
        return sum;
    }

    /** The limit on one file (or the total) for a measure, if the budget sets one. */
    @Nullable
    Limit limit(Measure measure, String file) {
        for (Limit limit : limits) {
            if (limit.measure() == measure && limit.file().equals(file)) {
                return limit;
            }
        }
        return null;
    }

    private static boolean isGlob(String name) {
        return name.indexOf('*') >= 0;
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append("[^/]*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    private static Kind kind(String value, String where) {
        for (Kind kind : Kind.values()) {
            if (kind.label().equals(value)) {
                return kind;
            }
        }
        throw new CliException(where + ": unknown class '" + value + "' (download, debug, report or vendor)");
    }

    private static Measure measure(String value, String where) {
        for (Measure measure : Measure.values()) {
            if (measure.label().equals(value)) {
                return measure;
            }
        }
        throw new CliException(where + ": unknown measure '" + value + "' (raw, gzip or brotli)");
    }

    private static long bytes(String value, String where) {
        try {
            long bytes = Long.parseLong(value.replace("_", ""));
            if (bytes < 0) {
                throw new NumberFormatException();
            }
            return bytes;
        } catch (NumberFormatException e) {
            throw new CliException(where + ": expected a byte count, found '" + value + "'", e);
        }
    }
}
