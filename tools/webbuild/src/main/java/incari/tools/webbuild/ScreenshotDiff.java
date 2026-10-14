package incari.tools.webbuild;

import incari.tools.build.CliException;
import incari.tools.wsbridge.WebSocketBridge;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * The screenshot-diff harness: renders fixed scenes with the desktop game (Mojang's OpenGL
 * backend, the reference) and with the browser build (the WebGL2 backend), and compares each pair
 * of screenshots by SSIM ({@link ImageDiff}) against the scenes file's threshold.
 *
 * <p>Both games join the same vanilla server (fresh world, fixed seed; {@link GameServer}), the
 * browser through the development WebSocket bridge, and take the same steps ({@link SceneScript}):
 * server commands over RCON place the camera and set time, weather and the scene's blocks and
 * entities, and keys and clicks go to the game. Every image is rendered on this machine at test
 * time into {@code --out}: they're pictures of Mojang's assets, never committed.
 *
 * <p>It also fails if the page logged a WebGL error or skipped a pipeline while it ran.
 *
 * <pre>
 * screenshot-diff --scenes &lt;scenes.txt&gt; --options &lt;options.txt&gt; --server-properties &lt;file&gt;
 *     --out &lt;dir&gt; --build &lt;teavm dir&gt; --file client.jar=&lt;jar&gt; --file shaders.json=&lt;json&gt;
 *     --desktop-launch &lt;file&gt; --version-json &lt;file&gt; --cache &lt;dir&gt;
 *     [--port 18210] [--gpu gl|vulkan|swiftshader] [--only &lt;scene&gt;,...] [--reuse-desktop]
 *     [--query &lt;extra page options&gt;]
 *     [--chrome &lt;path&gt;]
 * </pre>
 *
 * Ports: the server at {@code --port}, its RCON one above, then the bridge, the desktop game's
 * scene agent, the page's server and Chromium's DevTools.
 */
public final class ScreenshotDiff {

    /** The offline player both games join as: the same name gives the same skin. */
    static final String PLAYER = "IncariShot";

    /** The game time both worlds start the scenes at. */
    private static final long START_TICK = 40;

    private static final Pattern GAME_TIME = Pattern.compile("(\\d+) tick");

    private static final Pattern TICK_STEP = Pattern.compile("tick step (\\d+)");

    /** Ticks the frozen world runs for a player to join: chunks loaded, generated and lit. */
    private static final int JOIN_TICKS = 200;

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    /** Two frames at least this alike, a second and a half apart, mean the scene has settled. */
    private static final double SETTLED = 0.998;

    /** Console messages that fail the run: WebGL errors, and pipelines the backend couldn't draw. */
    static final Pattern PAGE_ERRORS = Pattern.compile(
            "(?i).*(GL_INVALID|WebGL: |CONTEXT_LOST|Skipping draws|doesn't compile|doesn't link|is incomplete).*");

    private ScreenshotDiff() {}

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
            Path scenes,
            Path options,
            Path serverProperties,
            Path out,
            Path build,
            Map<String, Path> files,
            Path desktopLaunch,
            Path versionJson,
            Path cache,
            int port,
            Chromium.Gpu gpu,
            Set<String> only,
            boolean reuseDesktop,
            @Nullable String chrome,
            String query) {}

    static int run(String[] args) throws IOException, InterruptedException {
        Options options = parse(args);
        SceneScript script = SceneScript.read(options.scenes());
        List<SceneScript.Scene> scenes = script.scenes().stream()
                .filter(scene -> options.only().isEmpty() || options.only().contains(scene.name()))
                .toList();
        if (scenes.isEmpty()) {
            throw new CliException("No scenes to run (--only " + String.join(",", options.only()) + ")");
        }
        Path out = options.out();
        Files.createDirectories(out);
        int port = options.port();
        List<String> pageErrors = new ArrayList<>();
        String[] gpu = {"unknown"};
        List<String> startTimes = new ArrayList<>();
        if (!options.reuseDesktop() || !allShotsExist(out.resolve("desktop"), scenes)) {
            Path gameDirectory = out.resolve("desktop-game");
            Chromium.deleteRecursively(gameDirectory);
            Files.createDirectories(gameDirectory);
            Files.copy(options.options(), gameDirectory.resolve("options.txt"));
            session(options, startTimes, server -> {
                try (DesktopSceneClient desktop = DesktopSceneClient.start(
                        options.desktopLaunch(), gameDirectory, PLAYER, "127.0.0.1:" + port, port + 3, WIDTH, HEIGHT)) {
                    play(desktop, server, script, scenes, out.resolve("desktop"));
                }
            });
        } else {
            System.out.println("==> Reusing the desktop screenshots in " + out.resolve("desktop"));
        }

        // The desktop window is WIDTH x HEIGHT in the desktop's units; on a scaled display (a
        // Wayland scale of 1.25, say) its framebuffer has more pixels. The page gets exactly as
        // many, at one device pixel per CSS pixel, so both render the same number of pixels.
        BufferedImage reference = ImageIO.read(out.resolve("desktop")
                .resolve(scenes.getFirst().shots().getFirst() + ".png")
                .toFile());
        if (reference == null) {
            throw new IOException("The desktop game's first screenshot isn't readable");
        }
        int width = reference.getWidth();
        int height = reference.getHeight();
        Map<String, Path> files = new LinkedHashMap<>(options.files());
        files.put("/options.txt", options.options());
        String query = "server=ws://127.0.0.1:" + (port + 2) + "&name=" + PLAYER + "&options=options.txt"
                + (options.query().isEmpty() ? "" : "&" + options.query());
        session(options, startTimes, server -> {
            try (BrowserSceneClient browser = BrowserSceneClient.start(
                    options.build(),
                    files,
                    query,
                    options.chrome(),
                    options.gpu(),
                    port + 4,
                    port + 5,
                    width,
                    height)) {
                gpu[0] = browser.evaluate("(() => { const g = document.createElement('canvas').getContext('webgl2');"
                        + " const e = g && g.getExtension('WEBGL_debug_renderer_info');"
                        + " return e ? g.getParameter(e.UNMASKED_RENDERER_WEBGL) : 'unknown'; })()");
                System.out.println("==> The browser renders with " + gpu[0]);
                browser.console().quiet();
                play(browser, server, script, scenes, out.resolve("browser"));
                Files.write(
                        out.resolve("browser-console.txt"), browser.console().history());
                for (String message : browser.console().history()) {
                    if (PAGE_ERRORS.matcher(message).matches()) {
                        pageErrors.add(message);
                    }
                }
            }
        });
        System.out.println("==> Game time each world started the scenes at: " + String.join(", ", startTimes));
        return report(script, scenes, out, gpu[0], pageErrors);
    }

    /** What one game does with its server. */
    @FunctionalInterface
    private interface Session {
        void run(GameServer server) throws IOException, InterruptedException;
    }

    /**
     * Runs one game against a server of its own: a fresh world from the same seed, frozen as soon
     * as it's up, so each game sees the world at (almost) the same game time and every tick after
     * that is one the scenes step on purpose. Clouds and animated textures follow the game time,
     * so the two games only match if their worlds have run the same ticks.
     */
    private static void session(Options options, List<String> startTimes, Session session)
            throws IOException, InterruptedException {
        int port = options.port();
        try (GameServer server = GameServer.start(
                        options.versionJson(),
                        options.cache(),
                        options.out().resolve("server"),
                        options.serverProperties(),
                        port,
                        port + 1);
                WebSocketBridge bridge = WebSocketBridge.start(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port + 2),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        message -> {})) {
            server.command("tick freeze");
            startTimes.add(Long.toString(alignGameTime(server)));
            System.out.println("==> The browser joins through ws://"
                    + bridge.address().getHostString() + ":" + bridge.address().getPort());
            session.run(server);
            awaitLeft(server);
        }
    }

    /**
     * Steps a just-frozen world on to {@link #START_TICK}, so both worlds start the scenes at the
     * same game time: a server freezes a tick or three after it starts, a different number each
     * time, and rain and clouds move with the game time. Returns the game time reached.
     */
    private static long alignGameTime(GameServer server) throws IOException, InterruptedException {
        long now = gameTime(server);
        if (now > START_TICK) {
            throw new IOException("The world ran " + now + " ticks before it froze; raise START_TICK");
        }
        if (now < START_TICK) {
            server.command("tick step " + (START_TICK - now));
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((now = gameTime(server)) < START_TICK && System.nanoTime() < until) {
            Thread.sleep(100);
        }
        return now;
    }

    /**
     * Runs {@code ticks} ticks of the frozen world and waits until they've all run, so every step
     * is whole: a second {@code tick step} while one is running would replace what's left of it.
     */
    private static String stepTicks(GameServer server, int ticks) throws IOException, InterruptedException {
        long target = gameTime(server) + ticks;
        String reply = server.command("tick step " + ticks);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30 + ticks / 5);
        while (gameTime(server) < target) {
            if (System.nanoTime() > until) {
                throw new IOException("The world didn't run " + ticks + " ticks in time");
            }
            Thread.sleep(100);
        }
        return reply;
    }

    private static long gameTime(GameServer server) throws IOException {
        Matcher matcher = GAME_TIME.matcher(server.command("time query gametime"));
        if (!matcher.find()) {
            throw new IOException("The server didn't say its game time");
        }
        return Long.parseLong(matcher.group(1));
    }

    /** Joins, runs the setup, then every scene, saving each screenshot in {@code directory}. */
    private static void play(
            SceneClient client, GameServer server, SceneScript script, List<SceneScript.Scene> scenes, Path directory)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        if (!server.awaitPlayer(PLAYER, 300)) {
            throw new IOException("The " + client.name() + " game never joined the server");
        }
        // The world is frozen: the ticks that load and light the chunks around the player.
        stepTicks(server, JOIN_TICKS);
        client.awaitWorld();
        System.out.println("==> The " + client.name() + " game joined; setting up");
        for (SceneScript.Step step : script.setup()) {
            step(client, server, step, directory);
        }
        for (SceneScript.Scene scene : scenes) {
            System.out.println("==> " + client.name() + ": scene " + scene.name() + " (" + scene.description() + ")");
            for (SceneScript.Step step : scene.steps()) {
                step(client, server, step, directory);
            }
        }
    }

    private static void step(SceneClient client, GameServer server, SceneScript.Step step, Path directory)
            throws IOException, InterruptedException {
        switch (step.kind()) {
            case "rcon" -> {
                Matcher ticks = TICK_STEP.matcher(step.value());
                String reply = ticks.matches()
                        ? stepTicks(server, Integer.parseInt(ticks.group(1)))
                        : server.command(step.value());
                System.out.println("    rcon " + step.value() + (reply.isEmpty() ? "" : " -> " + reply));
            }
            case "key" -> client.key(Keys.chord(step.value()));
            case "move", "click" -> {
                String[] parts = step.value().split(",", -1);
                double x = Double.parseDouble(parts[0].strip());
                double y = Double.parseDouble(parts[1].strip());
                if (step.kind().equals("click")) {
                    client.click(x, y);
                } else {
                    client.move(x, y);
                }
            }
            case "sleep" -> Thread.sleep(Long.parseLong(step.value()));
            case "settle" -> settle(client, directory.resolve("settling.png"));
            case "shot" -> {
                client.shot(directory.resolve(step.value() + ".png"));
                System.out.println("    shot " + step.value());
            }
            default -> throw new CliException("line " + step.line() + ": unknown step " + step.kind());
        }
    }

    /**
     * Waits until the picture stops changing (chunks built, fades done): three screenshots in a
     * row, a second and a half apart, nearly identical. Gives up after two minutes and says so;
     * the scene's screenshot then shows why.
     */
    private static void settle(SceneClient client, Path scratch) throws IOException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        BufferedImage previous = client.shot(scratch);
        int stable = 0;
        double last = 0;
        while (System.nanoTime() < until) {
            Thread.sleep(1500);
            BufferedImage current = client.shot(scratch);
            last = ImageDiff.compare(previous, current).ssim();
            stable = last >= SETTLED ? stable + 1 : 0;
            if (stable >= 2) {
                return;
            }
            previous = current;
        }
        System.out.println("    (still changing after two minutes: SSIM " + format(last) + " between frames)");
    }

    /** Waits for the player to leave the server, so the next game joins a world without them. */
    private static void awaitLeft(GameServer server) throws IOException, InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < until && server.command("list").contains(PLAYER)) {
            Thread.sleep(500);
        }
    }

    private static boolean allShotsExist(Path directory, List<SceneScript.Scene> scenes) {
        return scenes.stream()
                .flatMap(scene -> scene.shots().stream())
                .allMatch(shot -> Files.isRegularFile(directory.resolve(shot + ".png")));
    }

    private static int report(
            SceneScript script, List<SceneScript.Scene> scenes, Path out, String gpu, List<String> pageErrors)
            throws IOException {
        Path diffs = out.resolve("diff");
        Files.createDirectories(diffs);
        StringBuilder report = new StringBuilder();
        report.append("Screenshot diff: desktop (Mojang's OpenGL backend) against the browser (WebGL2 on ")
                .append(gpu)
                .append(")\n");
        report.append("Threshold: SSIM >= ").append(script.threshold()).append("\n\n");
        report.append(String.format(Locale.ROOT, "%-16s %-26s %8s %9s  %s%n", "scene", "shot", "SSIM", "changed", ""));
        int failed = 0;
        for (SceneScript.Scene scene : scenes) {
            for (String shot : scene.shots()) {
                BufferedImage desktop = ImageIO.read(
                        out.resolve("desktop").resolve(shot + ".png").toFile());
                BufferedImage browser = ImageIO.read(
                        out.resolve("browser").resolve(shot + ".png").toFile());
                if (desktop == null || browser == null) {
                    throw new IOException("Missing screenshots for " + shot);
                }
                ImageDiff.Result result = ImageDiff.compare(desktop, browser);
                ImageIO.write(
                        result.heatmap(), "png", diffs.resolve(shot + ".png").toFile());
                boolean pass = result.ssim() >= script.threshold();
                if (!pass) {
                    failed++;
                }
                report.append(String.format(
                        Locale.ROOT,
                        "%-16s %-26s %8s %8.2f%%  %s%n",
                        scene.name(),
                        shot,
                        format(result.ssim()),
                        result.changed() * 100,
                        pass ? "pass" : "FAIL"));
            }
        }
        report.append('\n');
        if (pageErrors.isEmpty()) {
            report.append("The page logged no WebGL errors and skipped no pipelines.\n");
        } else {
            report.append("The page logged ").append(pageErrors.size()).append(" WebGL errors or skipped pipelines:\n");
            pageErrors.stream()
                    .distinct()
                    .limit(40)
                    .forEach(message -> report.append("  ").append(message).append('\n'));
        }
        report.append('\n')
                .append("Images: ")
                .append(out.resolve("desktop"))
                .append(", ")
                .append(out.resolve("browser"))
                .append(", heat maps in ")
                .append(diffs)
                .append('\n');
        System.out.print(report);
        Files.writeString(out.resolve("report.txt"), report);
        return failed == 0 && pageErrors.isEmpty() ? 0 : 1;
    }

    private static String format(double ssim) {
        return String.format(Locale.ROOT, "%.4f", ssim);
    }

    private static Options parse(String[] args) {
        Map<String, String> single = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        boolean reuseDesktop = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--file" -> Serve.addFile(files, value(args, ++i, option));
                case "--dir" -> Serve.addDirectory(files, value(args, ++i, option));
                case "--reuse-desktop" -> reuseDesktop = true;
                case "--scenes",
                        "--options",
                        "--server-properties",
                        "--out",
                        "--build",
                        "--desktop-launch",
                        "--version-json",
                        "--cache",
                        "--port",
                        "--gpu",
                        "--only",
                        "--query",
                        "--chrome" -> single.put(option, value(args, ++i, option));
                default -> throw new CliException("Unknown option '" + option + "'");
            }
        }
        for (String required : List.of(
                "--scenes",
                "--options",
                "--server-properties",
                "--out",
                "--build",
                "--desktop-launch",
                "--version-json",
                "--cache")) {
            if (!single.containsKey(required)) {
                throw new CliException("screenshot-diff needs " + required);
            }
        }
        String only = single.getOrDefault("--only", "");
        return new Options(
                Path.of(single.get("--scenes")),
                Path.of(single.get("--options")),
                Path.of(single.get("--server-properties")),
                Path.of(single.get("--out")),
                Path.of(single.get("--build")),
                files,
                Path.of(single.get("--desktop-launch")),
                Path.of(single.get("--version-json")),
                Path.of(single.get("--cache")),
                Integer.parseInt(single.getOrDefault("--port", "18210")),
                Chromium.Gpu.parse(single.getOrDefault("--gpu", "gl")),
                only.isEmpty() ? Set.of() : Set.of(only.split(",")),
                reuseDesktop,
                single.get("--chrome"),
                single.getOrDefault("--query", ""));
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new CliException(option + " needs a value");
        }
        return args[index];
    }
}
