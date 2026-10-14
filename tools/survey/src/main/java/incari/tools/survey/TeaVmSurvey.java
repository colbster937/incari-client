package incari.tools.survey;

import incari.tools.build.FileTrees;
import incari.tools.build.Log;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.teavm.dependency.DependencyInfo;
import org.teavm.diagnostics.DefaultProblemTextConsumer;
import org.teavm.diagnostics.Problem;
import org.teavm.model.CallLocation;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReference;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.TextLocation;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMToolException;
import org.teavm.tooling.TeaVMToolLog;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Runs TeaVM's WebAssembly GC compile over the client and turns what it learns into findings.
 *
 * <p>Two sources from one compile: TeaVM's own errors (classes, methods and fields its class
 * library doesn't have), and native (JNI) methods reachable in the game or its libraries,
 * which TeaVM only rejects at code generation, a stage it never reaches while errors remain.
 * It also returns the reachable game methods, so {@link BytecodeScanner} looks only at code
 * that will actually be compiled.
 *
 * <p>Errors TeaVM reports inside JDK code it copied from the host (see {@link Origins}) keep
 * that JDK class as their caller. Walking them up the call graph to game code doesn't work:
 * virtual dispatch through {@code Object.hashCode()} and friends links almost everything to
 * everything.
 *
 * <p>TeaVM doesn't follow calls into code it can't find, so this is the first layer: stubbing
 * these items will uncover more behind them. Rerun the survey after each round.
 */
public final class TeaVmSurvey {

    /**
     * What the compile rounds found, and what TeaVM will compile.
     *
     * @param reachableAppMethods game and library methods TeaVM will compile, as
     *                            {@code owner#name(descriptor)}; the bytecode scan only looks inside these
     */
    public record Result(List<Finding> findings, Set<String> reachableClasses, Set<String> reachableAppMethods) {}

    /** Rounds are cheap early and grow as more of the game becomes reachable; this is a safety net. */
    private static final int MAX_ROUNDS = 40;

    private TeaVmSurvey() {}

    /**
     * Compiles in rounds, stubbing whatever each round reports missing, until a round finds
     * nothing new (see {@link Stubs}). Findings accumulate across rounds; reachability and
     * natives come from the last, most complete round.
     */
    public static Result run(String mainClass, Map<Path, String> appJars, Path rootsDir, Origins origins, Path workDir)
            throws IOException, TeaVMToolException {
        Stubs stubs = new Stubs(ReferenceIndex.build(appJars.keySet()));
        Set<Finding> findings = new LinkedHashSet<>();
        Path stubDir = workDir.resolve("stubs");
        Analysis last = null;
        for (int round = 1; ; round++) {
            FileTrees.deleteRecursively(stubDir);
            Files.createDirectories(stubDir);
            stubs.writeClasses(stubDir);
            StubTransformer.members = Map.copyOf(stubs.extraMembers());

            List<Path> classpath = new ArrayList<>(appJars.keySet());
            classpath.add(rootsDir);
            classpath.add(stubDir);
            long start = System.nanoTime();
            last = compile(mainClass, classpath, origins, workDir.resolve("teavm-out"));
            findings.addAll(last.findings);

            int added = 0;
            for (Problem problem : last.problems) {
                added += stubFor(problem, stubs, origins) ? 1 : 0;
            }
            // TeaVM names a missing class once; stub every one that reachable code uses.
            for (Finding f : BytecodeScanner.scan(appJars, last.reachableClasses, last.reachableAppMethods, origins)) {
                if (f.kind() == Finding.Kind.MISSING_CLASS || f.kind() == Finding.Kind.HOST_JDK) {
                    added += stubs.addAbsentClass(f.owner()) ? 1 : 0;
                }
            }
            Log.info(String.format(
                    Locale.ROOT,
                    "round %d: %,d reachable classes, %d problems, %d new stubs (%ds)",
                    round,
                    last.reachableClasses.size(),
                    last.problems.size(),
                    added,
                    (System.nanoTime() - start) / 1_000_000_000));
            if (added == 0) {
                break;
            }
            if (round == MAX_ROUNDS) {
                Log.warn("Stopped after " + MAX_ROUNDS
                        + " rounds with stubs still being added; the survey is incomplete.");
                break;
            }
        }
        findings.addAll(last.natives);
        Log.info("Stubbed " + stubs.absentClassCount() + " absent classes and " + stubs.extraMemberCount()
                + " missing members to reach this point");
        return new Result(
                List.copyOf(findings), new TreeSet<>(last.reachableClasses), Set.copyOf(last.reachableAppMethods));
    }

    /** Registers a stub for what {@code problem} reports missing. True if it's new. */
    private static boolean stubFor(Problem problem, Stubs stubs, Origins origins) {
        Object[] params = problem.getParams();
        String text = problem.getText();
        if (text.contains("which is missing in the classpath")
                && params.length > 1
                && params[1] instanceof String cls) {
            return stubs.addAbsentClass(cls);
        }
        if (params.length == 0) {
            return false;
        }
        return switch (params[0]) {
            case String cls when text.startsWith("Class {{c0}} was not found") -> stubs.addAbsentClass(cls);
            case MethodReference method
            when text.startsWith("Method {{m0}} was not found") ->
                stubs.addMethod(method, stubbedAsClass(origins, method.getClassName()));
            case FieldReference field
            when text.startsWith("Field {{f0}} was not found") ->
                stubs.addField(field, stubbedAsClass(origins, field.getClassName()));
            default -> false;
        };
    }

    /** Classes TeaVM can't see at all get generated stub class files, not transformer members. */
    static boolean stubbedAsClass(Origins origins, String className) {
        String origin = origins.of(className);
        return Origins.ABSENT.equals(origin) || Origins.HOST_JDK.equals(origin);
    }

    /**
     * Hides the host JDK's class files from TeaVM.
     *
     * <p>Left visible, TeaVM compiles any JDK class its class library lacks straight from the
     * running JDK, and follows it into JDK internals (security, JMX, {@code jdk.internal}).
     * That analysis is huge (tens of thousands of classes) and says nothing useful: the work
     * item is the game's use of the missing class, not everything behind it. Hidden, each such
     * class is reported missing and stubbed like any other. Class loading (TeaVM's own plugins)
     * is untouched; only bytecode reads are filtered.
     */
    private static Analysis compile(String mainClass, List<Path> classpath, Origins origins, Path outputDir)
            throws IOException, TeaVMToolException {
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = classpath.get(i).toUri().toURL();
        }
        // Parent = the survey's own loader, which carries TeaVM's class library (the
        // browser-side java.* implementations the game will actually run against), minus the
        // host JDK's class files (see HostJdkHidingLoader).
        ClassLoader parent = new HostJdkHidingLoader(TeaVmSurvey.class.getClassLoader(), origins);
        try (URLClassLoader loader = new URLClassLoader(urls, parent)) {
            TeaVMTool tool = new TeaVMTool();
            tool.setTargetType(TeaVMTargetType.WEBASSEMBLY_GC);
            tool.setMainClass(mainClass);
            tool.setClassLoader(loader);
            tool.setTargetDirectory(outputDir.toFile());
            tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
            tool.setIncremental(false);
            // Every client method is already a root (see Roots), so precise per-variable type
            // flow adds little, and on the whole client it needs well over 20 GB. The fast mode
            // resolves virtual calls by class hierarchy instead.
            tool.setFastDependencyAnalysis(true);
            tool.setLog(new QuietLog());
            tool.getTransformers().add(StubTransformer.class.getName());
            tool.generate();

            DependencyInfo deps = tool.getDependencyInfo();
            if (deps == null) {
                throw new IllegalStateException("TeaVM produced no dependency information");
            }
            Analysis analysis = new Analysis(deps, origins);
            for (Problem problem : tool.getProblemProvider().getProblems()) {
                analysis.addProblem(problem);
            }
            analysis.scanReachableMethods();
            return analysis;
        }
    }

    private static final class Analysis {
        private final DependencyInfo deps;
        private final Origins origins;
        private final List<Problem> problems = new ArrayList<>();
        private final Set<Finding> findings = new LinkedHashSet<>();
        private final Set<Finding> natives = new LinkedHashSet<>();
        private final Set<String> reachableAppMethods = new HashSet<>();
        private final Set<String> reachableClasses;

        Analysis(DependencyInfo deps, Origins origins) {
            this.deps = deps;
            this.origins = origins;
            this.reachableClasses = new HashSet<>(deps.getReachableClasses());
        }

        void addProblem(Problem problem) {
            problems.add(problem);
            findings.add(toFinding(problem));
        }

        /** Finds reachable natives, and records which game methods are reachable at all. */
        void scanReachableMethods() {
            for (MethodReference method : deps.getReachableMethods()) {
                String owner = method.getClassName();
                if (!origins.isApp(owner)) {
                    continue;
                }
                reachableAppMethods.add(
                        owner + "#" + method.getName() + method.getDescriptor().signatureToString());
                if (isNative(method)) {
                    natives.add(new Finding(
                            Finding.Kind.NATIVE,
                            owner + "#" + method.getName(),
                            method.toString(),
                            owner,
                            method.getDescriptor().toString(),
                            -1,
                            null));
                }
            }
        }

        private boolean isNative(MethodReference method) {
            ClassReader cls = deps.getClassSource().get(method.getClassName());
            MethodReader reader = cls != null ? cls.getMethod(method.getDescriptor()) : null;
            return reader != null && reader.hasModifier(ElementModifier.NATIVE);
        }
    }

    static Finding toFinding(Problem problem) {
        String text = problem.getText();
        Object[] params = problem.getParams();
        Object first = params.length > 0 ? params[0] : null;

        Finding.Kind kind;
        if (text.startsWith("Class {{c0}} was not found")) {
            kind = Finding.Kind.MISSING_CLASS;
        } else if (text.startsWith("Method {{m0}} was not found")) {
            kind = Finding.Kind.MISSING_METHOD;
        } else if (text.startsWith("Field {{f0}} was not found")) {
            kind = Finding.Kind.MISSING_FIELD;
        } else if (text.startsWith("Class {{c0}} is not supported")) {
            kind = Finding.Kind.UNSUPPORTED_CLASS;
        } else if (text.startsWith("Method {{m0}} is not supported")) {
            kind = Finding.Kind.UNSUPPORTED_METHOD;
        } else if (text.contains("which is missing in the classpath")) {
            kind = Finding.Kind.MISSING_SUPERTYPE;
            // The interesting symbol is the missing supertype, not the class extending it.
            first = params.length > 1 ? params[1] : first;
        } else if (text.startsWith("Bootstrap method {{m0}} was not found")) {
            kind = Finding.Kind.MISSING_BOOTSTRAP;
        } else {
            kind = Finding.Kind.OTHER;
        }

        String symbol;
        String detail;
        switch (first) {
            case MethodReference method -> {
                symbol = method.getClassName() + "#" + method.getName();
                detail = method.toString();
            }
            case FieldReference field -> {
                symbol = field.getClassName() + "#" + field.getFieldName();
                detail = field.toString();
            }
            case String className -> {
                symbol = className;
                detail = className;
            }
            case null, default -> {
                symbol = kind == Finding.Kind.OTHER ? render(problem) : String.valueOf(first);
                detail = render(problem);
            }
        }

        CallLocation location = problem.getLocation();
        MethodReference callerMethod = location != null ? location.getMethod() : null;
        TextLocation source = location != null ? location.getSourceLocation() : null;
        return new Finding(
                kind,
                symbol,
                detail,
                callerMethod != null ? callerMethod.getClassName() : null,
                callerMethod != null ? callerMethod.getDescriptor().toString() : null,
                source != null && !source.isEmpty() ? source.getLine() : -1,
                null);
    }

    private static String render(Problem problem) {
        DefaultProblemTextConsumer consumer = new DefaultProblemTextConsumer();
        problem.render(consumer);
        return consumer.getText();
    }

    /** TeaVM's progress chatter is noise here; its problems are collected separately. */
    private static final class QuietLog implements TeaVMToolLog {
        @Override
        public void info(String text) {}

        @Override
        public void debug(String text) {}

        @Override
        public void warning(String text) {}

        @Override
        public void error(String text) {}

        @Override
        public void info(String text, Throwable e) {}

        @Override
        public void debug(String text, Throwable e) {}

        @Override
        public void warning(String text, Throwable e) {
            Log.warn("TeaVM: " + text + ": " + e);
        }

        @Override
        public void error(String text, Throwable e) {
            Log.warn("TeaVM error: " + text + ": " + e);
        }
    }
}
