package incari.tools.webbuild;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import incari.tools.build.CliException;
import incari.tools.wsbridge.WebSocketBridge;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The benchmark runner (ADR 0021): plays fixed scenarios in the browser build against a
 * fresh vanilla world and reports what the page's profiler measured (frame rate and 1% low, where
 * each frame's time went, draw calls, the mesh queue, memory, garbage collection pauses), with the
 * load times and the download size, then holds the figures to the machine's baseline.
 *
 * <p>The world comes from a fixed seed ({@link GameServer}: the server jar from Mojang's metadata,
 * a fresh world each run), set up by the script's {@code server} steps before anyone joins. The page
 * is opened four times: on a cleared origin (cold start to the title screen), reloaded (warm
 * start), then into the server twice ({@code ?server=}, the join and the rejoin). Then the script's setup and scenarios
 * run ({@link BenchmarkScript}); a scenario's {@code measure} steps read the frames the page's
 * frame monitor kept in the meantime ({@code window.__incariFrames}) and trace the page's
 * garbage collections through the DevTools protocol.
 *
 * <p>The browser is headless Chromium on this machine, a window of it ({@code --headed}), or any
 * Chrome reachable over the DevTools protocol ({@code --connect <host>:<port>}): a Chromebook in
 * developer mode, which is how the reference devices are measured (docs/benchmark.md). The page
 * is then served on {@code --page-host}, an address of this machine the Chromebook can reach.
 *
 * <pre>
 * benchmark --script &lt;benchmark.txt&gt; --options &lt;options.txt&gt; --server-properties &lt;file&gt;
 *     --baseline &lt;baseline.properties&gt; --out &lt;dir&gt; --build &lt;teavm dir&gt; --file client.jar=&lt;jar&gt; ...
 *     --version-json &lt;file&gt; --cache &lt;dir&gt; [--machine &lt;name&gt;] [--update-baseline]
 *     [--only &lt;scenario&gt;,...] [--query &lt;extra page options&gt;] [--download-report &lt;json&gt;]
 *     [--history &lt;jsonl&gt;] [--port 18310] [--gpu gl|vulkan|swiftshader] [--headed] [--chrome &lt;path&gt;]
 *     [--cpu-throttle &lt;rate&gt;] [--gc-trace]
 *     [--connect &lt;host:port&gt; --page-host &lt;address&gt;]
 * </pre>
 *
 * Ports: the server at {@code --port}, its RCON one above, then the bridge, the page and Chromium's
 * DevTools. Exits 1 if a figure regressed past its rule (the baseline file lists them).
 */
public final class Benchmark {

    /** How long the first join's page is left to store what it keeps before the second join. */
    private static final long REJOIN_SETTLE_MILLIS = 3000;

    /** The singleplayer benchmark's world. */
    private static final String WORLD = "IncariBench";

    /** How long a new world runs before it's left: its spawn area loads, meshes and is stored. */
    private static final long CREATE_SETTLE_MILLIS = 15000;

    /** How long the chat takes to open or a command to go, on the slowest machine measured. */
    private static final long CHAT_MILLIS = 700;

    /** How long a command's answer may take to show in the page's console. */
    private static final long REPLY_MILLIS = 3000;

    /** The offline player the page joins as. */
    static final String PLAYER = "IncariBench";

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    /** How often a long wait moves the mouse, so the game never counts the player as idle. */
    private static final long NUDGE_MILLIS = 5000;

    /** How long the title screen runs before its memory is read: start-up's last background work. */
    private static final long TITLE_SETTLE_MILLIS = 5000;

    private static final double MEGABYTE = 1024 * 1024;

    private Benchmark() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (CliException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private record Options(
            Path script,
            Path options,
            Path serverProperties,
            Path baseline,
            @Nullable Path budgets,
            Path out,
            Path build,
            Map<String, Path> files,
            Path versionJson,
            Path cache,
            String machine,
            boolean updateBaseline,
            Set<String> only,
            String query,
            @Nullable Path downloadReport,
            @Nullable Path history,
            int port,
            Chromium.Gpu gpu,
            boolean headed,
            @Nullable String chrome,
            @Nullable String connect,
            String pageHost,
            boolean profile,
            boolean heapProfile,
            boolean heapSnapshot,
            @Nullable Path desktopLaunch,
            double cpuThrottle,
            boolean gcTrace,
            boolean singleplayer) {}

    /** Where the scenario being played saves its CPU profiles (--profile), or null. */
    private static @Nullable Path profiles;

    private static int profileCount;

    /** Where the scenario being played saves its GC traces (--gc-trace), or null. */
    private static @Nullable Path gcTraces;

    /** Where the scenario's measured frames go, a row each: build/benchmark/<scenario>.frames.csv. */
    private static @Nullable Path frameRows;

    private static final String FRAME_HEADER = "end,interval,took,waited,tick,extract,render,gpu,draws,"
            + "terrainDraws,terrainSections,meshQueue,heapMB,nativeMB,poolMillis,poolLate\n";

    /** Appends a measured stretch's frame rows, as the page kept them, for looking at frame by frame. */
    private static void writeFrameRows(double[] table) throws IOException {
        Path file = frameRows;
        if (file == null) {
            return;
        }
        StringBuilder out = new StringBuilder(Files.exists(file) ? "" : FRAME_HEADER);
        for (int i = 0; i < table.length; i += FrameStats.COLUMNS) {
            for (int c = 0; c < FrameStats.COLUMNS; c++) {
                if (c > 0) {
                    out.append(',');
                }
                double value = table[i + c];
                out.append(Double.isNaN(value) ? "" : PerfBaseline.format(value));
            }
            out.append('\n');
        }
        Files.writeString(file, out, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static int gcTraceCount;

    static int run(String[] args) throws IOException, InterruptedException {
        Options options = parse(args);
        BenchmarkScript script = BenchmarkScript.read(options.script());
        List<BenchmarkScript.Scenario> scenarios = script.scenarios().stream()
                .filter(scenario -> options.only().isEmpty() || options.only().contains(scenario.name()))
                .toList();
        if (scenarios.isEmpty()) {
            throw new CliException("No scenarios to run (--only " + String.join(",", options.only()) + ")");
        }
        Files.createDirectories(options.out());
        Map<String, Double> figures = new LinkedHashMap<>();
        Map<String, String> about = new LinkedHashMap<>();
        List<String> pageErrors = new ArrayList<>();
        about.put("machine", options.machine());
        about.put("date", Instant.now().toString());
        about.put("commit", commit());
        about.put("query", options.query());
        about.put("mode", options.singleplayer() ? "singleplayer" : "server");
        if (options.singleplayer()) {
            singleplayer(options, script, scenarios, figures, about, pageErrors);
        } else if (!multiplayer(options, script, scenarios, figures, about, pageErrors)) {
            return 0;
        }
        if (options.downloadReport() != null && Files.isRegularFile(options.downloadReport())) {
            JsonObject report = JsonParser.parseString(Files.readString(options.downloadReport()))
                    .getAsJsonObject();
            figures.put(
                    "download.gzipBytes",
                    report.getAsJsonObject("totalDownloaded").get("gzip").getAsDouble());
        }
        int status = report(options, about, figures);
        if (!pageErrors.isEmpty()) {
            // Figures from a page that couldn't draw everything aren't figures of the game.
            System.err.println("Benchmark: the page logged WebGL errors or skipped pipelines:");
            pageErrors.stream().limit(40).forEach(message -> System.err.println("  " + message));
            return 1;
        }
        return status;
    }

    /**
     * The server's benchmark: a vanilla server with the script's world, the page joining it. False
     * if the desktop game played instead ({@code --desktop-launch}), which has no figures.
     */
    private static boolean multiplayer(
            Options options,
            BenchmarkScript script,
            List<BenchmarkScript.Scenario> scenarios,
            Map<String, Double> figures,
            Map<String, String> about,
            List<String> pageErrors)
            throws IOException, InterruptedException {
        int port = options.port();
        InetAddress host = InetAddress.getByName(options.pageHost());
        try (GameServer server = GameServer.start(
                        options.versionJson(),
                        options.cache(),
                        options.out().resolve("server"),
                        options.serverProperties(),
                        port,
                        port + 1);
                WebSocketBridge bridge = WebSocketBridge.start(
                        new InetSocketAddress(host, port + 2),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        message -> {})) {
            System.out.println("==> The page joins through ws://"
                    + bridge.address().getHostString() + ":" + bridge.address().getPort() + "; preparing the world");
            for (BenchmarkScript.Step step : script.server()) {
                step(null, server, step, null, null);
            }
            Path desktopLaunch = options.desktopLaunch();
            if (desktopLaunch != null) {
                desktopAllocations(options, desktopLaunch, server, script, scenarios);
                return false;
            }
            Map<String, Path> files = new LinkedHashMap<>(options.files());
            files.put("/options.txt", options.options());
            HttpServer http = Serve.start(options.build(), host, port + 3, files);
            String origin = "http://" + options.pageHost() + ":" + (port + 3);
            String page = origin + "/?"
                    + PageQuery.withBuild("name=" + PLAYER + "&options=options.txt"
                            + (options.query().isEmpty() ? "" : "&" + options.query()));
            try (Page browser = Page.open(options, origin, port + 4)) {
                describe(browser, about);
                load(
                        browser,
                        page,
                        "&server=ws://" + options.pageHost() + ":" + (port + 2) + "&profile",
                        figures,
                        options);
                if (!server.awaitPlayer(PLAYER, 120)) {
                    throw new IOException("The page never joined the server");
                }
                play(browser, server, script, scenarios, figures, options);
                pageErrors.addAll(pageErrors(browser, options));
            } finally {
                http.stop(0);
            }
        }
        return true;
    }

    /**
     * The singleplayer benchmark: no server of this machine's, but the page's own (ADR 0039), on a
     * world it creates from {@code --server-properties}' seed, the server benchmark's terrain. The
     * script's {@code rcon} steps are typed into the game's chat, as a player runs a command, so
     * its options show commands in chat (the server benchmark's hide chat). The loads: the page
     * started on a cleared origin to the title screen, the world created, saved and quit to the
     * title screen, and opened again from a reload, where the scenarios play.
     */
    private static void singleplayer(
            Options options,
            BenchmarkScript script,
            List<BenchmarkScript.Scenario> scenarios,
            Map<String, Double> figures,
            Map<String, String> about,
            List<String> pageErrors)
            throws IOException, InterruptedException {
        if (!script.server().isEmpty()) {
            throw new CliException(
                    options.script() + ": singleplayer has no server section: there's no server to prepare");
        }
        long seed = seed(options.serverProperties());
        Path gameOptions = options.out().resolve("singleplayer-options.txt");
        Files.write(
                gameOptions,
                Files.readAllLines(options.options()).stream()
                        .map(line -> line.startsWith("chatVisibility:") ? "chatVisibility:1" : line)
                        .toList());
        int port = options.port();
        InetAddress host = InetAddress.getByName(options.pageHost());
        Map<String, Path> files = new LinkedHashMap<>(options.files());
        files.put("/options.txt", gameOptions);
        HttpServer http = Serve.start(options.build(), host, port + 3, files);
        String origin = "http://" + options.pageHost() + ":" + (port + 3);
        String page = origin + "/?"
                + PageQuery.withBuild("name=" + PLAYER + "&options=options.txt&profile&world=" + WORLD
                        + (options.query().isEmpty() ? "" : "&" + options.query()));
        try (Page browser = Page.open(options, origin, port + 4)) {
            describe(browser, about);
            browser.devTools.attachWorkers();
            System.out.println("==> Creating the world, from seed " + seed);
            browser.clearOrigin();
            browser.go(page + "&seed=" + seed);
            double title = browser.awaitMark("title", 600);
            figures.put("load.coldTitleMillis", title);
            figures.put("load.createMillis", awaitWorld(browser, options, "create") - title);
            // The new world's spawn area loads, meshes and is stored before it's left.
            browser.idle(CREATE_SETTLE_MILLIS);
            System.out.println("==> Save and Quit to Title");
            figures.put("load.saveQuitMillis", browser.leaveWorld(600));
            // What the page keeps once a world is left and its worker gone.
            browser.idle(TITLE_SETTLE_MILLIS);
            liveMemory(browser, "load.leftHeapLiveMB", "load.leftNativeLiveMB", figures, options, "left");
            System.out.println("==> Opening the world again");
            browser.go(page);
            figures.put("load.openMillis", awaitWorld(browser, options, "open"));
            figures.forEach((name, value) -> System.out.println("    " + name + " = " + PerfBaseline.format(value)));
            play(browser, null, script, scenarios, figures, options);
            pageErrors.addAll(pageErrors(browser, options));
        } finally {
            http.stop(0);
        }
    }

    /**
     * Waits for the page's "world" mark; with --profile, also records the server worker's CPU profile
     * ({@code <name>.server.cpuprofile}) from when it shows up to then: what generating or loading
     * the world costs it.
     */
    private static double awaitWorld(Page browser, Options options, String name)
            throws IOException, InterruptedException {
        if (!options.profile()) {
            return browser.awaitMark("world", 900);
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        String worker = browser.serverWorker();
        while (worker == null && System.nanoTime() < until) {
            Thread.sleep(50);
            worker = browser.serverWorker();
        }
        if (worker == null) {
            throw new IOException("No server worker started for '" + name + "'");
        }
        browser.devTools.startProfile(worker);
        double mark = browser.awaitMark("world", 900);
        Path file = options.out().resolve(name + ".server.cpuprofile");
        Files.writeString(file, browser.devTools.stopProfile(worker));
        System.out.println("    the server worker's CPU profile in " + file);
        return mark;
    }

    /** The seed in a server's properties ({@code level-seed}): the singleplayer world's seed. */
    private static long seed(Path serverProperties) throws IOException {
        java.util.Properties properties = new java.util.Properties();
        try (var reader = Files.newBufferedReader(serverProperties)) {
            properties.load(reader);
        }
        String seed = properties.getProperty("level-seed", "").strip();
        if (!seed.matches("-?\\d+")) {
            throw new CliException(
                    serverProperties + ": level-seed must be a number for singleplayer, not '" + seed + "'");
        }
        return Long.parseLong(seed);
    }

    /** The browser's version and its renderer, for the report. */
    private static void describe(Page browser, Map<String, String> about) throws IOException, InterruptedException {
        about.put("browser", browser.devTools.browserVersion());
        about.put(
                "renderer",
                unquote(browser.evaluate("(() => { const g = document.createElement('canvas').getContext('webgl2');"
                        + " const e = g && g.getExtension('WEBGL_debug_renderer_info');"
                        + " return e ? g.getParameter(e.UNMASKED_RENDERER_WEBGL) : 'unknown'; })()")));
        System.out.println("==> " + about.get("browser") + " renders with " + about.get("renderer"));
    }

    /**
     * The script's setup, then its scenarios, each measured. {@code server} is null in
     * singleplayer, where commands go through the chat and the server worker's ticks are measured too.
     */
    private static void play(
            Page browser,
            @Nullable GameServer server,
            BenchmarkScript script,
            List<BenchmarkScript.Scenario> scenarios,
            Map<String, Double> figures,
            Options options)
            throws IOException, InterruptedException {
        System.out.println("==> In the world; setting up");
        for (BenchmarkScript.Step step : script.setup()) {
            step(browser, server, step, null, null);
        }
        for (BenchmarkScript.Scenario scenario : scenarios) {
            System.out.println("==> Scenario " + scenario.name() + " (" + scenario.description() + ")");
            profiles = options.profile() ? options.out().resolve(scenario.name()) : null;
            profileCount = 0;
            gcTraces = options.gcTrace() ? options.out().resolve(scenario.name()) : null;
            gcTraceCount = 0;
            frameRows = options.out().resolve(scenario.name() + ".frames.csv");
            Files.deleteIfExists(frameRows);
            FrameStats stats = new FrameStats();
            ServerTickStats ticks = server == null ? new ServerTickStats() : null;
            for (BenchmarkScript.Step step : scenario.steps()) {
                step(browser, server, step, stats, ticks);
            }
            stats.figures().forEach((name, value) -> figures.put(scenario.name() + "." + name, value));
            if (ticks != null) {
                Map<String, Double> serverFigures = ticks.figures();
                serverFigures.forEach((name, value) -> figures.put(scenario.name() + "." + name, value));
                if (serverFigures.getOrDefault("serverPausedTicks", 0.0) > 0) {
                    System.out.println("    warning: the server was paused for some of the measured ticks");
                }
            }
            liveMemory(
                    browser,
                    scenario.name() + ".heapLiveMB",
                    scenario.name() + ".nativeLiveMB",
                    figures,
                    options,
                    scenario.name());
            if (server == null) {
                String worker = browser.serverWorker();
                double heap = worker == null ? Double.NaN : browser.devTools.collectAndMeasure(worker);
                figures.put(scenario.name() + ".workerHeapLiveMB", heap / MEGABYTE);
                System.out.println("    the server worker's heap after a collection: "
                        + PerfBaseline.format(heap / MEGABYTE) + " MB");
                if (options.heapSnapshot() && worker != null) {
                    Path file = options.out().resolve(scenario.name() + ".server.heapsnapshot");
                    browser.devTools.heapSnapshot(file, worker);
                    Path summary = options.out().resolve(scenario.name() + ".server.heapsnapshot.txt");
                    Files.writeString(summary, HeapSnapshotSummary.summarize(file, 150));
                    System.out.println(
                            "    the server worker's heap snapshot in " + file + ", summed by type in " + summary);
                }
            }
        }
    }

    /** WebGL errors and skipped pipelines the page that played logged, each once. */
    private static List<String> pageErrors(Page browser, Options options) throws IOException {
        List<String> console = browser.consoleHistory();
        Files.write(options.out().resolve("console.txt"), console);
        // The page that played, from its loader's first line: the start-ups before it are
        // navigated away from while they may still be loading, and a context torn down
        // mid-frame logs errors of its own (Chrome's log arrives late, so a count taken at
        // the navigation isn't the boundary).
        int joinedFrom = 0;
        for (int i = 0; i < console.size(); i++) {
            if (console.get(i).contains("[Incari] Loader: build")) {
                joinedFrom = i;
            }
        }
        return console.subList(joinedFrom, console.size()).stream()
                .filter(message -> ScreenshotDiff.PAGE_ERRORS.matcher(message).matches())
                .distinct()
                .toList();
    }

    /**
     * The four loads: cold (a cleared origin) and warm (a reload) to the title screen, then into
     * the server, twice. Each is timed from the navigation, by the page's own marks.
     */
    private static void load(Page browser, String page, String join, Map<String, Double> figures, Options options)
            throws IOException, InterruptedException {
        System.out.println("==> Cold start");
        browser.clearOrigin();
        browser.go(page);
        figures.put("load.coldFirstFrameMillis", browser.awaitMark("firstFrame", 600));
        figures.put("load.coldTitleMillis", browser.awaitMark("title", 600));
        System.out.println("==> Warm start");
        if (options.heapProfile()) {
            browser.devTools.startHeapSampling();
        }
        if (options.profile()) {
            // Start-up's CPU profile; the profiler slows the page, so this start isn't the one timed then.
            browser.devTools.startProfile();
            browser.go(page);
            browser.awaitMark("title", 600);
            Path file = options.out().resolve("warm-start.cpuprofile");
            Files.writeString(file, browser.devTools.stopProfile());
            System.out.println("    CPU profile in " + file);
        }
        browser.go(page);
        figures.put("load.warmTitleMillis", browser.awaitMark("title", 600));
        // Where the warm start's time went: to the first frame (the module, the game's
        // set-up and bootstrap), then to the end of the first resource reload, then the fade.
        figures.put("load.warmFirstFrameMillis", browser.awaitMark("firstFrame", 10));
        figures.put("load.warmReloadedMillis", browser.awaitMark("reloaded", 10));
        // What the title screen holds once start-up's garbage is gone (the heap budget, ADR 0025).
        browser.idle(TITLE_SETTLE_MILLIS);
        liveMemory(browser, "load.titleHeapLiveMB", "load.titleNativeLiveMB", figures, options, "title");
        System.out.println("==> Joining");
        if (options.heapProfile()) {
            browser.devTools.startHeapSampling();
        }
        browser.go(page + join);
        figures.put("load.joinMillis", browser.awaitMark("world", 600));
        // And again from a reload, as a player's every join after the first (ADR 0032): what the
        // first join kept (the mesher's tables) is read back. The wait lets the storage worker
        // finish writing it.
        System.out.println("==> Joining again");
        browser.idle(REJOIN_SETTLE_MILLIS);
        browser.go(page + join);
        figures.put("load.rejoinMillis", browser.awaitMark("world", 600));
        figures.forEach((name, value) -> System.out.println("    " + name + " = " + PerfBaseline.format(value)));
    }

    /**
     * --desktop-jfr: the scenarios played by the desktop game (Mojang's OpenGL backend, on the JVM)
     * instead of the page, each measured stretch recorded by the Flight Recorder, to
     * {@code <scenario>-<n>.jfr}, with its allocations by site beside it ({@code <scenario>-<n>.allocations.txt}).
     * The page's own heap profiler can't see into WebAssembly; the game's code allocates the same
     * objects on the JVM, which shows where (ADR 0025's allocation fixes). No figures: the desktop's
     * frames aren't the page's.
     */
    private static void desktopAllocations(
            Options options,
            Path launch,
            GameServer server,
            BenchmarkScript script,
            List<BenchmarkScript.Scenario> scenarios)
            throws IOException, InterruptedException {
        Path gameDirectory = options.out().resolve("desktop-game");
        Chromium.deleteRecursively(gameDirectory);
        Files.createDirectories(gameDirectory);
        Files.copy(options.options(), gameDirectory.resolve("options.txt"));
        try (DesktopSceneClient desktop = DesktopSceneClient.start(
                launch, gameDirectory, PLAYER, "127.0.0.1:" + options.port(), options.port() + 4, WIDTH, HEIGHT)) {
            if (!server.awaitPlayer(PLAYER, 180)) {
                throw new IOException("The desktop game never joined the server");
            }
            desktop.awaitWorld();
            System.out.println("==> Joined; setting up");
            for (BenchmarkScript.Step step : script.setup()) {
                step(null, server, step, null, null);
            }
            for (BenchmarkScript.Scenario scenario : scenarios) {
                System.out.println(
                        "==> Scenario " + scenario.name() + " (" + scenario.description() + "), on the desktop");
                int stretch = 0;
                for (BenchmarkScript.Step step : scenario.steps()) {
                    if (!step.kind().equals("measure")) {
                        step(null, server, step, null, null);
                        continue;
                    }
                    // A scenario measured in several stretches (combat's waves) gets a recording each.
                    String name = scenario.name() + "-" + ++stretch;
                    Path recording = options.out().resolve(name + ".jfr").toAbsolutePath();
                    desktop.jcmd("JFR.start", "name=" + name, "settings=profile");
                    Thread.sleep(TimeUnit.SECONDS.toMillis(Integer.parseInt(step.value())));
                    desktop.jcmd("JFR.stop", "name=" + name, "filename=" + recording);
                    Path summary = options.out().resolve(name + ".allocations.txt");
                    Files.writeString(
                            summary,
                            jfrView(recording, "allocation-by-site") + jfrView(recording, "allocation-by-class"));
                    System.out.println("    recording in " + recording + ", allocations by site in " + summary);
                }
            }
        }
    }

    /** {@code jfr view <view>} of {@code recording}, from the JDK this runs on. */
    private static String jfrView(Path recording, String view) throws IOException, InterruptedException {
        Path jfr = Path.of(System.getProperty("java.home"), "bin", "jfr");
        Process process = new ProcessBuilder(jfr.toString(), "view", "--width", "220", view, recording.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output + "\n";
    }

    /**
     * The memory the page holds after a full collection: the JavaScript heap (which holds the
     * WebAssembly GC objects, the game's Java heap) and the linear memory blocks in use (the game's
     * off-heap buffers, ADR 0008), as figures {@code heapKey} and {@code nativeKey}.
     * The scenarios' maxima count garbage not yet collected; these count what the game keeps. With
     * --heap-profile, also the heap sampler's live objects by allocating stack, to {@code <name>.heapprofile};
     * with --heap-snapshot, a heap snapshot, {@code <name>.heapsnapshot}, and its sizes by type beside it.
     */
    private static void liveMemory(
            Page browser, String heapKey, String nativeKey, Map<String, Double> figures, Options options, String name)
            throws IOException, InterruptedException {
        browser.devTools.collectGarbage();
        double heap = number(browser.evaluate("performance.memory ? performance.memory.usedJSHeapSize : NaN"));
        double linear = number(browser.evaluate("window.__incariLinear ? window.__incariLinear.live : NaN"));
        figures.put(heapKey, heap / MEGABYTE);
        figures.put(nativeKey, linear / MEGABYTE);
        System.out.println("    live after a collection: heap " + PerfBaseline.format(heap / MEGABYTE)
                + " MB, linear memory " + PerfBaseline.format(linear / MEGABYTE) + " MB");
        if (options.heapProfile()) {
            Path file = options.out().resolve(name + ".heapprofile");
            Files.writeString(file, browser.devTools.heapSamples());
            System.out.println("    heap samples in " + file);
        }
        if (options.heapSnapshot()) {
            Path file = options.out().resolve(name + ".heapsnapshot");
            browser.devTools.heapSnapshot(file);
            Path summary = options.out().resolve(name + ".heapsnapshot.txt");
            Files.writeString(summary, HeapSnapshotSummary.summarize(file, 150));
            System.out.println("    heap snapshot in " + file + ", summed by type in " + summary);
        }
    }

    private static double number(String json) {
        try {
            return Double.parseDouble(json);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Plays one step. Without a {@code server} (singleplayer), commands are typed into the game's
     * chat and a {@code rconwait} reads its answer from the page's console; {@code ticks} then
     * collects the server worker's ticks in measured stretches.
     */
    private static void step(
            @Nullable Page browser,
            @Nullable GameServer server,
            BenchmarkScript.Step step,
            @Nullable FrameStats stats,
            @Nullable ServerTickStats ticks)
            throws IOException, InterruptedException {
        switch (step.kind()) {
            case "rcon" -> {
                if (server == null) {
                    requirePage(browser, step).command(step.value());
                    System.out.println("    chat /" + step.value());
                    return;
                }
                String reply = server.command(step.value());
                System.out.println("    rcon " + step.value() + (reply.isEmpty() ? "" : " -> " + reply));
            }
            case "rconwait" -> {
                int arrow = step.value().indexOf(" => ");
                String command = step.value().substring(0, arrow);
                String expected = step.value().substring(arrow + " => ".length());
                long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                String reply = answer(browser, server, step, command);
                while (!reply.contains(expected)) {
                    if (System.nanoTime() > until) {
                        throw new IOException("line " + step.line() + ": the server still answers '" + reply + "'");
                    }
                    Thread.sleep(1000);
                    reply = answer(browser, server, step, command);
                }
                System.out.println("    rconwait " + command + " -> " + reply);
            }
            case "sleep" -> {
                if (browser != null) {
                    browser.idle(Long.parseLong(step.value()));
                } else {
                    Thread.sleep(Long.parseLong(step.value()));
                }
            }
            case "key" -> requirePage(browser, step).key(Keys.chord(step.value()));
            case "shot" -> {
                // A screenshot, to see the page is where the script thinks: build/benchmark/<name>.png.
                Path file = Path.of("build", "benchmark", step.value() + ".png");
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.write(file, requirePage(browser, step).devTools.screenshot());
                System.out.println("    screenshot " + file.toAbsolutePath());
            }
            case "move" -> {
                String[] parts = step.value().split(",", -1);
                requirePage(browser, step)
                        .move(Double.parseDouble(parts[0].strip()), Double.parseDouble(parts[1].strip()));
            }
            case "measure" -> {
                Page page = requirePage(browser, step);
                if (stats == null) {
                    throw new CliException("line " + step.line() + ": only scenarios measure");
                }
                int seconds = Integer.parseInt(step.value());
                System.out.println("    measuring for " + seconds + " s");
                long from = page.frameCount();
                String worker = ticks == null ? null : page.serverWorker();
                long fromTick = worker == null ? 0 : page.serverTickCount(worker);
                long started = System.nanoTime();
                Path profile = profiles;
                if (profile != null) {
                    page.devTools.startProfile();
                    if (worker != null) {
                        page.devTools.startProfile(worker);
                    }
                }
                Path gcTrace = gcTraces;
                page.devTools.startTracing(
                        gcTrace == null
                                ? List.of(GcStats.CATEGORY)
                                : List.of(GcStats.CATEGORY, GcStats.DETAIL_CATEGORY));
                page.idle(TimeUnit.SECONDS.toMillis(seconds));
                List<JsonObject> traced = page.devTools.stopTracing();
                stats.add(GcStats.of(traced));
                if (gcTrace != null) {
                    Path file = Path.of(gcTrace + "-" + ++gcTraceCount + ".gc.txt");
                    Files.writeString(file, GcStats.timeline(traced));
                    System.out.println("    GC timeline in " + file);
                }
                if (profile != null) {
                    Path file = Path.of(profile + "-" + ++profileCount + ".cpuprofile");
                    Files.writeString(file, page.devTools.stopProfile());
                    System.out.println("    CPU profile in " + file);
                    if (worker != null) {
                        Path serverFile = Path.of(profile + "-" + profileCount + ".server.cpuprofile");
                        Files.writeString(serverFile, page.devTools.stopProfile(worker));
                        System.out.println("    the server worker's CPU profile in " + serverFile);
                    }
                }
                double[] table = page.frames(from);
                stats.add(table);
                writeFrameRows(table);
                System.out.println("    " + stats.frames() + " frames so far");
                if (ticks != null) {
                    if (worker == null) {
                        throw new IOException("line " + step.line() + ": no server worker to measure");
                    }
                    ticks.add(page.serverTicks(worker, fromTick), (System.nanoTime() - started) / 1e9);
                    System.out.println("    " + ticks.ticks() + " server ticks so far");
                }
            }
            default -> throw new CliException("line " + step.line() + ": unknown step " + step.kind());
        }
    }

    /** A command's answer: RCON's reply, or in singleplayer what the page logged after it. */
    private static String answer(
            @Nullable Page browser, @Nullable GameServer server, BenchmarkScript.Step step, String command)
            throws IOException, InterruptedException {
        return server != null
                ? server.command(command)
                : requirePage(browser, step).commandReply(command);
    }

    private static Page requirePage(@Nullable Page browser, BenchmarkScript.Step step) {
        if (browser == null) {
            throw new CliException("line " + step.line() + ": no page yet (the server section only runs commands)");
        }
        return browser;
    }

    /** Writes the report, compares against the baseline, and updates it if asked; the exit status. */
    private static int report(Options options, Map<String, String> about, Map<String, Double> figures)
            throws IOException {
        PerfBaseline baseline = PerfBaseline.parse(
                Files.isRegularFile(options.baseline()) ? Files.readAllLines(options.baseline()) : List.of(),
                options.baseline().toString());
        List<PerfBaseline.Comparison> comparisons = baseline.compare(options.machine(), figures);
        List<PerfBaseline.Comparison> regressions =
                comparisons.stream().filter(PerfBaseline.Comparison::regressed).toList();
        Path budgetFile = options.budgets();
        List<PerfBudget.Check> budgetChecks = budgetFile != null && Files.isRegularFile(budgetFile)
                ? PerfBudget.parse(Files.readAllLines(budgetFile), budgetFile.toString())
                        .check(options.machine(), figures)
                : List.of();
        List<PerfBudget.Check> overBudget =
                budgetChecks.stream().filter(PerfBudget.Check::over).toList();

        JsonObject json = new JsonObject();
        about.forEach(json::addProperty);
        JsonObject values = new JsonObject();
        figures.forEach((key, value) -> values.addProperty(key, value.isNaN() ? null : value));
        json.add("figures", values);
        JsonArray regressed = new JsonArray();
        regressions.forEach(comparison -> regressed.add(comparison.key()));
        json.add("regressions", regressed);
        JsonArray over = new JsonArray();
        overBudget.forEach(check -> over.add(check.figure()));
        json.add("overBudget", over);
        String text =
                new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(json);
        Files.writeString(options.out().resolve("report.json"), text + "\n");

        List<Map<String, Double>> history = new ArrayList<>();
        if (options.history() != null) {
            history = history(options.history(), options.machine());
            Path historyFile = options.history();
            Path parent = historyFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    historyFile,
                    new GsonBuilder().serializeNulls().create().toJson(json) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            history.add(figures);
        }

        String markdown = BenchmarkReport.markdown(
                about, figures, budgetChecks, comparisons, baseline.figures(options.machine()), history);
        Files.writeString(options.out().resolve("report.md"), markdown);
        System.out.println();
        System.out.print(markdown);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            // The CI job's summary: the pull request's performance report.
            Files.writeString(Path.of(summary), markdown, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.println("==> Report: " + options.out().resolve("report.md"));

        if (!overBudget.isEmpty()) {
            // Budgets hold whatever the baseline says: re-recording it doesn't excuse them.
            System.err.println("Benchmark: " + overBudget.size() + " figures are over their budgets ("
                    + String.join(
                            ", ",
                            overBudget.stream().map(PerfBudget.Check::figure).toList())
                    + "; app-web/benchmark/budgets.properties).");
        }
        if (options.updateBaseline()) {
            Files.write(options.baseline(), baseline.withFigures(options.machine(), figures));
            System.out.println("==> Wrote " + options.machine() + "'s figures to " + options.baseline());
            return overBudget.isEmpty() ? 0 : 1;
        }
        if (!overBudget.isEmpty()) {
            return 1;
        }
        if (baseline.figures(options.machine()).isEmpty()) {
            System.out.println("==> No baseline for " + options.machine() + " in " + options.baseline()
                    + "; record one with -Pbenchmark.updateBaseline");
            return 0;
        }
        if (!regressions.isEmpty()) {
            System.err.println("Benchmark: " + regressions.size() + " figures regressed past their rules ("
                    + String.join(
                            ", ",
                            regressions.stream()
                                    .map(PerfBaseline.Comparison::key)
                                    .toList())
                    + "). Fix the regression, or re-record the baseline in this pull request and say why.");
            return 1;
        }
        return 0;
    }

    /** {@code machine}'s earlier runs, oldest first, from the history file (one JSON report a line). */
    private static List<Map<String, Double>> history(Path file, String machine) throws IOException {
        List<Map<String, Double>> runs = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return runs;
        }
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject run = JsonParser.parseString(line).getAsJsonObject();
            JsonElement name = run.get("machine");
            if (name == null || !name.getAsString().equals(machine)) {
                continue;
            }
            Map<String, Double> figures = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry :
                    run.getAsJsonObject("figures").entrySet()) {
                figures.put(
                        entry.getKey(),
                        entry.getValue().isJsonNull()
                                ? Double.NaN
                                : entry.getValue().getAsDouble());
            }
            runs.add(figures);
        }
        return runs;
    }

    private static String commit() {
        String sha = System.getenv("GITHUB_SHA");
        if (sha != null && !sha.isBlank()) {
            return sha;
        }
        try {
            Process git = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return git.waitFor() == 0 ? out : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static String unquote(String json) {
        JsonElement value = JsonParser.parseString(json);
        return value.isJsonPrimitive() ? value.getAsString() : json;
    }

    /**
     * The page, through the DevTools protocol, in a Chromium this runner started or in a tab of one
     * it reached over the network.
     */
    private static final class Page implements AutoCloseable {

        private final @Nullable Chromium chromium;
        final DevTools devTools;
        private final String origin;
        private final List<String> held = new ArrayList<>();
        private boolean nudge;

        private Page(@Nullable Chromium chromium, DevTools devTools, String origin) {
            this.chromium = chromium;
            this.devTools = devTools;
            this.origin = origin;
        }

        static Page open(Options options, String origin, int devToolsPort) throws IOException, InterruptedException {
            if (options.connect() != null) {
                String endpoint = remoteEndpoint(options.connect());
                System.out.println("==> Driving the browser at " + options.connect());
                DevTools devTools = DevTools.openTab(endpoint, "about:blank");
                devTools.collectConsole();
                return throttled(new Page(null, devTools, origin), options);
            }
            // Precise heap figures: without the flag Chrome rounds them and updates them rarely.
            Chromium chromium = Chromium.open(
                    options.chrome(),
                    "about:blank",
                    options.gpu(),
                    devToolsPort,
                    WIDTH + "," + HEIGHT,
                    !options.headed(),
                    List.of("--enable-precise-memory-info"));
            try {
                long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
                DevTools devTools = DevTools.connect(chromium.console().devToolsUrl(deadline));
                chromium.console().quiet();
                if (!options.headed()) {
                    devTools.resize(WIDTH, HEIGHT, 1);
                }
                return throttled(new Page(chromium, devTools, origin), options);
            } catch (IOException | RuntimeException e) {
                chromium.close();
                throw e;
            }
        }

        /**
         * Applies {@code --cpu-throttle}: Chrome runs the page's CPU that many times slower, a stand-in
         * for a slower machine's CPU (ADR 0027). Its figures go under their own machine name.
         */
        private static Page throttled(Page page, Options options) throws IOException, InterruptedException {
            if (options.cpuThrottle() > 1) {
                System.out.println("==> The page's CPU runs " + options.cpuThrottle() + " times slower");
                page.devTools.throttleCpu(options.cpuThrottle());
            }
            return page;
        }

        /** The browser endpoint of a Chrome started with {@code --remote-debugging-port}, at {@code host:port}. */
        private static String remoteEndpoint(String hostPort) throws IOException, InterruptedException {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://" + hostPort + "/json/version"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            String url = JsonParser.parseString(response.body())
                    .getAsJsonObject()
                    .get("webSocketDebuggerUrl")
                    .getAsString();
            // Chrome names itself as it sees itself (localhost); the runner reaches it by the address given.
            URI given = URI.create(url);
            return "ws://" + hostPort + given.getRawPath();
        }

        String evaluate(String expression) throws IOException, InterruptedException {
            return devTools.evaluate(expression, false);
        }

        /** Clears the page's origin: caches, the service worker, stored files. */
        void clearOrigin() throws IOException, InterruptedException {
            devTools.clearOrigin(origin);
        }

        /** Goes to {@code url}, a fresh document even if it's the one already open. */
        void go(String url) throws IOException, InterruptedException {
            evaluate("window.__incariBenchmarkStale = true");
            devTools.navigate(url);
        }

        /**
         * Waits for the page's start-up mark {@code name} in the document {@link #go} opened; its
         * time, in milliseconds from the navigation.
         */
        double awaitMark(String name, int seconds) throws IOException, InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            String expression = "(!window.__incariBenchmarkStale && window.__incariFrames"
                    + " && window.__incariFrames.marks['" + name + "']) || 0";
            while (System.nanoTime() < until) {
                double value;
                try {
                    value = Double.parseDouble(evaluate(expression));
                } catch (IOException | NumberFormatException e) {
                    // Mid-navigation: the old document is gone and the new one isn't ready.
                    value = 0;
                }
                if (value > 0) {
                    return value;
                }
                idle(500);
            }
            throw new IOException("The page never reached '" + name + "' in " + seconds + " s");
        }

        /** Lets the page run, moving the mouse now and then so the game doesn't throttle as if idle. */
        void idle(long millis) throws InterruptedException, IOException {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                long left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
                if (left <= 0) {
                    return;
                }
                Thread.sleep(Math.min(left, NUDGE_MILLIS));
                nudge = !nudge;
                move(nudge ? 0.5 : 0.501, 0.5);
            }
        }

        /**
         * Asks the game to leave its world, as Save and Quit to Title does ({@code QuickWorlds}),
         * and waits until it's back at the title screen with the world stored; the milliseconds that took.
         */
        double leaveWorld(int seconds) throws IOException, InterruptedException {
            double asked = Double.parseDouble(evaluate("(() => { const f = window.__incariFrames;"
                    + " f.requests = f.requests || {}; f.requests.leaveWorld = true; return performance.now(); })()"));
            awaitMark("leftWorld", seconds);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!evaluate("window.incariStorage.pending() === 0").equals("true")) {
                if (System.nanoTime() > until) {
                    throw new IOException("The world was never stored in " + seconds + " s");
                }
                Thread.sleep(100);
            }
            return Double.parseDouble(evaluate("performance.now()")) - asked;
        }

        /** Types {@code command} into the game's chat and sends it. */
        void command(String command) throws IOException, InterruptedException {
            key(List.of("Slash"));
            Thread.sleep(CHAT_MILLIS);
            devTools.type(command);
            key(List.of("Enter"));
            Thread.sleep(CHAT_MILLIS);
        }

        /** Sends {@code command} through the chat; the console lines the page logged in the meantime, joined. */
        String commandReply(String command) throws IOException, InterruptedException {
            int from = consoleHistory().size();
            command(command);
            Thread.sleep(REPLY_MILLIS);
            List<String> console = consoleHistory();
            return String.join(" | ", console.subList(Math.min(from, console.size()), console.size()));
        }

        /** Singleplayer's server worker's DevTools session (ADR 0039), or null if it isn't running. */
        @Nullable
        String serverWorker() {
            return devTools.workerSession("incari-server");
        }

        /** The number the server worker's next tick will get ({@code WorkerProfiling}). */
        long serverTickCount(String worker) throws IOException, InterruptedException {
            return (long) Double.parseDouble(
                    devTools.evaluateIn(worker, "self.__incariServer ? self.__incariServer.count : 0"));
        }

        /** The server worker's tick table from tick {@code from} on, flat. */
        double[] serverTicks(String worker, long from) throws IOException, InterruptedException {
            JsonArray values = JsonParser.parseString(
                            devTools.evaluateIn(worker, "self.__incariServer.rows(" + from + ")"))
                    .getAsJsonArray();
            double[] out = new double[values.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = values.get(i).getAsDouble();
            }
            return out;
        }

        /** The number the page's next frame will get. */
        long frameCount() throws IOException, InterruptedException {
            return (long) Double.parseDouble(evaluate("window.__incariFrames ? window.__incariFrames.count : 0"));
        }

        /** The page's frame table from frame {@code from} on, flat. */
        double[] frames(long from) throws IOException, InterruptedException {
            String json = evaluate("window.__incariFrames.rows(" + from + ").map(v => Number.isNaN(v) ? null : v)");
            JsonArray values = JsonParser.parseString(json).getAsJsonArray();
            double[] out = new double[values.size()];
            for (int i = 0; i < out.length; i++) {
                JsonElement value = values.get(i);
                out[i] = value.isJsonNull() ? Double.NaN : value.getAsDouble();
            }
            return out;
        }

        void move(double x, double y) throws IOException, InterruptedException {
            double[] viewport = devTools.viewport();
            devTools.move(x * viewport[0], y * viewport[1]);
        }

        void key(List<String> chord) throws IOException, InterruptedException {
            for (String code : chord) {
                held.add(code);
                devTools.key(code, true, modifiers());
            }
            for (String code : chord.reversed()) {
                held.remove(code);
                devTools.key(code, false, modifiers());
            }
        }

        private int modifiers() {
            int bits = 0;
            for (String code : held) {
                bits |= Keys.modifierBit(code);
            }
            return bits;
        }

        List<String> consoleHistory() {
            Chromium started = chromium;
            return started == null ? devTools.console() : started.console().history();
        }

        @Override
        public void close() throws IOException {
            devTools.close();
            Chromium started = chromium;
            if (started != null) {
                started.close();
            }
        }
    }

    private static final Pattern MACHINE = Pattern.compile("[A-Za-z0-9_-]+");

    private static Options parse(String[] args) throws IOException {
        Map<String, String> single = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        boolean update = false;
        boolean headed = false;
        boolean profile = false;
        boolean heapProfile = false;
        boolean heapSnapshot = false;
        boolean gcTrace = false;
        boolean singleplayer = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--file" -> Serve.addFile(files, value(args, ++i, option));
                case "--dir" -> Serve.addDirectory(files, value(args, ++i, option));
                case "--update-baseline" -> update = true;
                case "--headed" -> headed = true;
                case "--profile" -> profile = true;
                case "--heap-profile" -> heapProfile = true;
                case "--heap-snapshot" -> heapSnapshot = true;
                case "--gc-trace" -> gcTrace = true;
                case "--singleplayer" -> singleplayer = true;
                case "--script",
                        "--options",
                        "--server-properties",
                        "--baseline",
                        "--budgets",
                        "--out",
                        "--build",
                        "--version-json",
                        "--cache",
                        "--machine",
                        "--only",
                        "--query",
                        "--download-report",
                        "--history",
                        "--port",
                        "--gpu",
                        "--chrome",
                        "--connect",
                        "--desktop-launch",
                        "--page-host",
                        "--cpu-throttle" -> single.put(option, value(args, ++i, option));
                default -> throw new CliException("Unknown option '" + option + "'");
            }
        }
        for (String required : List.of(
                "--script",
                "--options",
                "--server-properties",
                "--baseline",
                "--out",
                "--build",
                "--version-json",
                "--cache")) {
            if (!single.containsKey(required)) {
                throw new CliException("benchmark needs " + required);
            }
        }
        String machine =
                single.getOrDefault("--machine", InetAddress.getLocalHost().getHostName());
        if (!MACHINE.matcher(machine).matches()) {
            throw new CliException("A machine name is letters, digits, - and _: '" + machine + "'");
        }
        if (singleplayer && single.containsKey("--desktop-launch")) {
            throw new CliException("--desktop-launch plays the server's scenarios, not --singleplayer's");
        }
        if (single.containsKey("--connect") && !single.containsKey("--page-host")) {
            throw new CliException("--connect needs --page-host: an address of this machine the browser can reach");
        }
        String only = single.getOrDefault("--only", "");
        return new Options(
                Path.of(single.get("--script")),
                Path.of(single.get("--options")),
                Path.of(single.get("--server-properties")),
                Path.of(single.get("--baseline")),
                single.containsKey("--budgets") ? Path.of(single.get("--budgets")) : null,
                Path.of(single.get("--out")),
                Path.of(single.get("--build")),
                files,
                Path.of(single.get("--version-json")),
                Path.of(single.get("--cache")),
                machine,
                update,
                only.isEmpty() ? Set.of() : Set.of(only.split(",")),
                single.getOrDefault("--query", ""),
                single.containsKey("--download-report") ? Path.of(single.get("--download-report")) : null,
                single.containsKey("--history") ? Path.of(single.get("--history")) : null,
                Integer.parseInt(single.getOrDefault("--port", "18310")),
                Chromium.Gpu.parse(single.getOrDefault("--gpu", "gl")),
                headed,
                single.get("--chrome"),
                single.get("--connect"),
                single.getOrDefault("--page-host", "127.0.0.1"),
                profile,
                heapProfile,
                heapSnapshot,
                single.containsKey("--desktop-launch") ? Path.of(single.get("--desktop-launch")) : null,
                Double.parseDouble(single.getOrDefault("--cpu-throttle", "1")),
                gcTrace,
                singleplayer);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new CliException(option + " needs a value");
        }
        return args[index];
    }
}
