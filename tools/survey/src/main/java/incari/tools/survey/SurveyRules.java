package incari.tools.survey;

import incari.tools.build.CliException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The survey's policy file ({@code tools/survey/rules.txt}): which libraries are compiled
 * with the game, and how each finding is triaged.
 *
 * <p>Three kinds of lines. Library lines decide the classpath:
 * <pre>
 * library org.lwjgl:  replace  Platform layer: graphics, windowing, input, audio
 * </pre>
 * {@code keep} compiles the library with the game. {@code replace} and {@code delete} leave it
 * off the classpath, so every call into it shows up as a missing symbol at its call site.
 *
 * <p>No-root lines keep parts of the client jar that never run in the client (data
 * generators, the dedicated server) out of the analysis roots (see {@link Roots}):
 * <pre>
 * no-root net.minecraft.data.**  Data generators run at Mojang's build time
 * </pre>
 *
 * <p>Triage lines sort findings. The first rule whose selectors all match wins:
 * <pre>
 * delete  caller=net.minecraft.util.profiling.jfr.**  JFR profiling, stripped
 * shim    symbol=java.nio.file.**                     Virtual filesystem over OPFS
 * stub    kind=reflection symbol=java.lang.Class#getGenericSuperclass  ...
 * </pre>
 * Selectors: {@code symbol} (the missing or used thing, {@code owner#member}),
 * {@code caller} (the class that needs work), {@code origin} ({@code minecraft} or a
 * library name), {@code kind} ({@code reflection}, {@code native}, {@code host-jdk}, or a
 * TeaVM problem such as {@code missing-method}). Globs: {@code *} matches within one name
 * segment, {@code **} matches anything.
 */
public record SurveyRules(List<LibraryRule> libraries, List<NoRootRule> noRoots, List<TriageRule> triage) {

    public enum Category {
        STUB("Stub", "A no-op or trivial implementation is enough."),
        SHIM("Shim", "Needs a real implementation, over a browser API or simpler Java."),
        REWRITE("Rewrite", "The calling code has to change: a patch or a replacement class."),
        DELETE("Delete", "The feature is stripped from the browser build.");

        private final String title;
        private final String meaning;

        Category(String title, String meaning) {
            this.title = title;
            this.meaning = meaning;
        }

        public String title() {
            return title;
        }

        public String meaning() {
            return meaning;
        }
    }

    public enum LibraryAction {
        KEEP,
        REPLACE,
        DELETE
    }

    public record NoRootRule(Pattern classes, String reason) {}

    /** Matches Maven coordinates by prefix, e.g. {@code "org.lwjgl:"} or {@code "io.netty:netty-buffer:"}. */
    public record LibraryRule(String coordinatePrefix, LibraryAction action, String reason) {}

    public record TriageRule(
            Category category,
            @Nullable Pattern symbol,
            @Nullable Pattern caller,
            @Nullable Pattern origin,
            @Nullable String kind,
            String reason,
            int line) {

        boolean matches(Finding finding, String kindName, @Nullable String origin) {
            return (symbol == null
                            || symbol.matcher(finding.symbol()).matches()
                            || (finding.member() != null
                                    && symbol.matcher(finding.owner()).matches()
                                    && !symbol.pattern().contains("#")))
                    && (caller == null
                            || (finding.caller() != null
                                    && caller.matcher(finding.caller()).matches()))
                    && (this.origin == null
                            || (origin != null && this.origin.matcher(origin).matches()))
                    && (kind == null || kind.equals(kindName));
        }
    }

    public static SurveyRules load(Path file) throws IOException {
        return parse(Files.readAllLines(file), file.getFileName().toString());
    }

    static SurveyRules parse(List<String> lines, String origin) {
        List<LibraryRule> libraries = new ArrayList<>();
        List<NoRootRule> noRoots = new ArrayList<>();
        List<TriageRule> triage = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int hash = raw.indexOf(" #");
            String line = (raw.startsWith("#") ? "" : hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            String where = origin + ":" + (i + 1);
            String[] words = line.split("\\s+");
            if (words[0].equals("library")) {
                if (words.length < 4) {
                    throw new CliException(
                            where + ": expected: library <coordinate prefix> <keep|replace|delete> <reason>");
                }
                LibraryAction action = parseEnum(LibraryAction.class, words[2], where);
                libraries.add(new LibraryRule(words[1], action, rest(words, 3)));
                continue;
            }
            if (words[0].equals("no-root")) {
                if (words.length < 3) {
                    throw new CliException(where + ": expected: no-root <class glob> <reason>");
                }
                noRoots.add(new NoRootRule(glob(words[1]), rest(words, 2)));
                continue;
            }
            Category category = parseEnum(Category.class, words[0], where);
            @Nullable Pattern symbol = null;
            @Nullable Pattern caller = null;
            @Nullable Pattern originPattern = null;
            @Nullable String kind = null;
            int w = 1;
            for (; w < words.length && words[w].contains("="); w++) {
                String[] kv = words[w].split("=", 2);
                switch (kv[0]) {
                    case "symbol" -> symbol = glob(kv[1]);
                    case "caller" -> caller = glob(kv[1]);
                    case "origin" -> originPattern = glob(kv[1]);
                    case "kind" -> kind = kv[1];
                    default -> throw new CliException(where + ": unknown selector '" + kv[0] + "'");
                }
            }
            if (w == 1) {
                throw new CliException(where + ": a triage rule needs at least one selector");
            }
            if (w >= words.length) {
                throw new CliException(where + ": a triage rule needs a reason after its selectors");
            }
            triage.add(new TriageRule(category, symbol, caller, originPattern, kind, rest(words, w), i + 1));
        }
        return new SurveyRules(List.copyOf(libraries), List.copyOf(noRoots), List.copyOf(triage));
    }

    /** The action for a library; libraries with no rule are an error, so none slips in unreviewed. */
    public LibraryRule library(String coordinates) {
        for (LibraryRule rule : libraries) {
            if (coordinates.startsWith(rule.coordinatePrefix())) {
                return rule;
            }
        }
        throw new CliException("No library rule in rules.txt covers " + coordinates
                + ". Decide whether the browser build keeps, replaces or deletes it.");
    }

    /** Whether a client class becomes an analysis root (see {@link Roots}). */
    public boolean isRooted(String className) {
        for (NoRootRule rule : noRoots) {
            if (rule.classes().matcher(className).matches()) {
                return false;
            }
        }
        return true;
    }

    /** The first matching rule, or {@code null} when nothing matches (reported as unclassified). */
    public @Nullable TriageRule classify(Finding finding, String kindName, @Nullable String origin) {
        for (TriageRule rule : triage) {
            if (rule.matches(finding, kindName, origin)) {
                return rule;
            }
        }
        return null;
    }

    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^.#]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static String rest(String[] words, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(words, from, words.length));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String where) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CliException(where + ": unknown " + type.getSimpleName() + " '" + value + "'", e);
        }
    }
}
