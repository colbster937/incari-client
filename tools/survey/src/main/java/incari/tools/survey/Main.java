package incari.tools.survey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import incari.tools.build.BuildConfig;
import incari.tools.build.CliException;
import incari.tools.build.FileTrees;
import incari.tools.build.GameFiles;
import incari.tools.build.Log;
import incari.tools.build.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code ./incari survey}: the TeaVM compile survey.
 *
 * <p>Compiles the pinned client with TeaVM (WebAssembly GC), collects everything that stands
 * between it and a browser build, triages each finding with {@code tools/survey/rules.txt},
 * and writes the outputs below. {@code --reclassify} skips the (slow) compile and reuses the
 * last run's saved TeaVM result, for iterating on the rules.
 * <ul>
 *   <li>{@code docs/port-backlog.md}: counts per category and one row per work item. Tracked.
 *       It holds API names and counts only, never game code.
 *   <li>{@code work/survey/<version>/findings.json}: every finding with its call site. Git-ignored.
 * </ul>
 */
public final class Main {

    static final String ENTRY_POINT = "net.minecraft.client.main.Main";
    static final String RULES_FILE = "tools/survey/rules.txt";
    static final String BACKLOG_FILE = "docs/port-backlog.md";

    /**
     * TeaVM compiles JDK classes it has no replacement for straight from the running JDK, so
     * the JDK version changes the result. It also can't read class files newer than Java 25.
     */
    static final int REQUIRED_JDK = 25;

    private Main() {}

    public static void main(String[] args) {
        try {
            run(List.of(args));
        } catch (CliException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    static void run(List<String> args) throws Exception {
        boolean reclassify = args.equals(List.of("--reclassify"));
        if (!args.isEmpty() && !reclassify) {
            throw new CliException("Usage: ./incari survey [--reclassify]");
        }
        int jdk = Runtime.version().feature();
        if (jdk != REQUIRED_JDK) {
            throw new CliException(
                    "The survey must run on JDK " + REQUIRED_JDK + " (this is JDK " + jdk + "). TeaVM compiles"
                            + " missing JDK classes from the running JDK, so another version changes the result.\n"
                            + "Point JAVA_HOME at a JDK " + REQUIRED_JDK
                            + ", e.g. JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./incari survey");
        }

        Workspace ws = Workspace.discover(Path.of(System.getProperty("incari.cwd", ".")));
        BuildConfig config = BuildConfig.load(ws.repoRoot());
        GameFiles game = GameFiles.load(ws, config);
        SurveyRules rules = SurveyRules.load(ws.repoRoot().resolve(RULES_FILE));

        Map<Path, String> jars = new LinkedHashMap<>();
        jars.put(game.clientJar(), Origins.MINECRAFT);
        List<LibraryDecision> libraries = new ArrayList<>();
        for (GameFiles.LibraryJar lib : game.libraries()) {
            String name = lib.library().name();
            SurveyRules.LibraryRule rule = rules.library(name);
            libraries.add(new LibraryDecision(name, rule.action(), rule.reason()));
            if (rule.action() == SurveyRules.LibraryAction.KEEP) {
                jars.put(lib.jar(), name.split(":", -1)[1]);
            }
        }

        Origins origins = new Origins(jars, Main.class.getClassLoader());
        Path outDir = ws.surveyDir(config.minecraftVersion());
        Files.createDirectories(outDir);

        Path teavmFindings = outDir.resolve("teavm-findings.json");
        Path reachableClassesFile = outDir.resolve("reachable-classes.txt");
        Path reachableMethodsFile = outDir.resolve("reachable-methods.txt");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        TeaVmSurvey.Result result;
        if (reclassify) {
            if (!Files.isRegularFile(teavmFindings)) {
                throw new CliException("No saved TeaVM result in " + outDir + ". Run ./incari survey first.");
            }
            Log.step("Reusing the saved TeaVM result (--reclassify)");
            result = new TeaVmSurvey.Result(
                    List.of(gson.fromJson(Files.readString(teavmFindings), Finding[].class)),
                    new java.util.TreeSet<>(Files.readAllLines(reachableClassesFile)),
                    new java.util.HashSet<>(Files.readAllLines(reachableMethodsFile)));
        } else {
            Log.step("Compiling " + game.version().id() + " with TeaVM " + teavmVersion()
                    + " (WebAssembly GC, in rounds; about a minute)");
            long start = System.nanoTime();
            Path rootsDir = outDir.resolve("roots");
            FileTrees.deleteRecursively(rootsDir);
            int rooted = Roots.generate(game.clientJar(), rules::isRooted, ENTRY_POINT, rootsDir);
            Log.info("Rooting " + rooted + " client classes, so the whole client is analysed, not only what"
                    + " main() reaches");
            result = TeaVmSurvey.run(Roots.ENTRY, jars, rootsDir, origins, outDir);
            Log.info("TeaVM analysis took " + (System.nanoTime() - start) / 1_000_000_000 + "s, "
                    + result.reachableClasses().size() + " reachable classes");
            Files.write(reachableClassesFile, result.reachableClasses());
            Files.write(
                    reachableMethodsFile,
                    result.reachableAppMethods().stream().sorted().toList());
            Files.writeString(teavmFindings, gson.toJson(result.findings()));
        }

        Log.step("Scanning reachable code for reflection and host-JDK references");
        List<Finding> scanned =
                BytecodeScanner.scan(jars, result.reachableClasses(), result.reachableAppMethods(), origins);
        // For classes TeaVM can't see (libraries left off the classpath, host-JDK classes), the
        // scan lists every call site in reachable game code, while TeaVM names each one once
        // (or, once stubbed, per member).
        // Keep TeaVM's entries only where the scan can't see: callers outside the game's jars.
        Set<Finding> findings = new LinkedHashSet<>();
        for (Finding f : result.findings()) {
            if (f.caller() != null && f.caller().startsWith("incari.survey.roots.")) {
                continue; // raised by the synthetic roots' own calls; the scan covers the real callers
            }
            boolean coveredByScan =
                    switch (f.kind()) {
                        case MISSING_CLASS, MISSING_METHOD, MISSING_FIELD ->
                            TeaVmSurvey.stubbedAsClass(origins, f.owner())
                                    && f.caller() != null
                                    && origins.isApp(f.caller());
                        // TeaVM reports these without the extending class; the scan names every one.
                        case MISSING_SUPERTYPE -> true;
                        default -> false;
                    };
            if (!coveredByScan) {
                findings.add(f);
            }
        }
        findings.addAll(scanned);

        List<Classified> classified = new ArrayList<>();
        for (Finding finding : findings) {
            @Nullable String origin = finding.caller() != null ? origins.of(finding.caller()) : null;
            classified.add(new Classified(
                    finding, origin, rules.classify(finding, finding.kind().ruleName(), origin)));
        }

        List<Map<String, Object>> json = new ArrayList<>();
        for (Classified c : classified) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(
                    "category",
                    c.rule() != null ? c.rule().category().name().toLowerCase(java.util.Locale.ROOT) : "unclassified");
            entry.put("rule", c.rule() != null ? c.rule().reason() : null);
            entry.put("origin", c.origin());
            entry.put("finding", c.finding());
            json.add(entry);
        }
        Files.writeString(outDir.resolve("findings.json"), gson.toJson(json));
        Backlog backlog = new Backlog(
                game.version().id(),
                teavmVersion(),
                jdk,
                ENTRY_POINT,
                result.reachableClasses(),
                origins,
                libraries,
                classified);
        Files.writeString(ws.repoRoot().resolve(BACKLOG_FILE), backlog.render());

        Log.step("Wrote " + BACKLOG_FILE + " and work/survey/" + game.version().id() + "/findings.json");
        backlog.printSummary();
    }

    record LibraryDecision(String coordinates, SurveyRules.LibraryAction action, String reason) {}

    /** A finding with its caller's origin and the rule that triaged it ({@code null}: unclassified). */
    record Classified(Finding finding, @Nullable String origin, SurveyRules.@Nullable TriageRule rule) {}

    /** Set by the Gradle build from the version catalog; TeaVM's jars don't record their own version. */
    static String teavmVersion() {
        return System.getProperty("incari.teavm.version", "unknown");
    }
}
