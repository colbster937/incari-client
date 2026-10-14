package incari.tools.webbuild;

import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import incari.tools.build.CliException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks that the browser build's game loop runs: serves the output directory on a free loopback
 * port, opens it in headless Chromium, echoes the console, and succeeds once the expected message
 * has appeared often enough.
 *
 * <p>{@link Chromium} says where the browser comes from. WebGL runs on SwiftShader unless
 * {@code --gpu gl} or {@code --gpu vulkan} asks for the machine's GPU; {@code --gpu webgpu} also
 * gives WebGPU the machine's GPU.
 */
public final class BrowserCheck {

    private BrowserCheck() {}

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

    static int run(String[] args) throws IOException, InterruptedException {
        if (args.length < 1) {
            throw new CliException(
                    "Usage: browser-check <dir>|<page.html> [--file <name>=<path>]... [--dir <prefix>=<path>]..."
                            + " [--chrome <path>]"
                            + " [--seconds <n>] [--expect <regex>] [--count <n>] [--query <page query>] [--step <step>]..."
                            + " [--steps-file <file>] [--rcon <host:port>] [--gpu swiftshader|gl|vulkan|webgpu]");
        }
        Path directory = Path.of(args[0]);
        @Nullable String chrome = null;
        Chromium.Gpu gpu = Chromium.Gpu.SWIFTSHADER;
        Map<String, Path> files = new LinkedHashMap<>();
        String query = "";
        int seconds = 180;
        Pattern expect = Pattern.compile("\\[Incari\\] Main loop running.*");
        int count = 2;
        List<String> steps = new ArrayList<>();
        @Nullable String rcon = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--chrome" -> chrome = args[++i];
                case "--gpu" -> gpu = Chromium.Gpu.parse(args[++i]);
                case "--file" -> Serve.addFile(files, args[++i]);
                case "--dir" -> Serve.addDirectory(files, args[++i]);
                case "--query" -> query = args[++i];
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--expect" -> expect = Pattern.compile(args[++i]);
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--step" -> steps.add(args[++i]);
                case "--steps-file" -> steps.addAll(readSteps(Path.of(args[++i])));
                case "--rcon" -> rcon = args[++i];
                default -> throw new CliException("Unknown option '" + args[i] + "'");
            }
        }
        // A single-file build (SingleFile, ADR 0057) opens from the disk, as a player opens it.
        boolean singleFile =
                Files.isRegularFile(directory) && directory.toString().endsWith(".html");
        if (!singleFile && !Files.isRegularFile(directory.resolve("index.html"))) {
            throw new CliException(directory + " has no index.html; run ./gradlew :app-web:teavm first");
        }
        @Nullable HttpServer server = singleFile ? null : Serve.start(directory, 0, files);
        // The page names the player (Player), so Edit Profile doesn't open before the title screen
        // the steps expect; --query name= (empty) leaves it to the player, to check that screen.
        String page = query.matches("(^|.*&)name=.*") ? query : "name=Player" + (query.isEmpty() ? "" : "&" + query);
        String url = (server == null
                        ? directory.toAbsolutePath().toUri().toString()
                        : "http://localhost:" + server.getAddress().getPort() + "/")
                + "?" + PageQuery.withBuild(page);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        // Steps drive the page through the DevTools protocol, at a player's window size.
        try (Chromium chromium =
                Chromium.open(chrome, url, gpu, steps.isEmpty() ? -1 : 0, steps.isEmpty() ? null : "1280,720")) {
            PageConsole console = chromium.console();
            if (!console.await(expect, count, deadline)) {
                return 1;
            }
            System.out.println("==> Passed: saw " + count + " messages matching " + expect.pattern());
            if (steps.isEmpty()) {
                return 0;
            }
            String devToolsUrl = console.devToolsUrl(deadline);
            try (DevTools devTools = DevTools.connect(devToolsUrl);
                    StepState state = new StepState(url, rcon)) {
                state.clientJar = files.get("/client.jar");
                for (String step : steps) {
                    if (!runStep(step, devTools, console, deadline, state)) {
                        return 1;
                    }
                }
            }
            System.out.println("==> Passed: ran " + steps.size() + " steps");
            return 0;
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    /**
     * Runs one step; false if it failed. Positions are fractions of the viewport, so a step list
     * works at any window size:
     * <ul>
     *   <li>{@code sleep:<ms>}: let the page run
     *   <li>{@code wait:<regex>}: wait for a console message
     *   <li>{@code shot:<path>}: save a screenshot (PNG)
     *   <li>{@code move:<x>,<y>} and {@code click:<x>,<y>}: the mouse, as a player moves and clicks it
     *   <li>{@code type:<text>}: type text
     *   <li>{@code eval:<expression>}: print a JavaScript expression's value
     *   <li>{@code init:<script>}: run a script before the page's own on every load, and reload
     *       (to watch what the game does while it starts; follow it with a {@code wait:})
     *   <li>{@code reload}: reload the page, as a player would (follow it with a {@code wait:})
     *   <li>{@code key:<chord>}: press keys by {@code KeyboardEvent.code} and release them in
     *       reverse: {@code key:F3}, {@code key:Control+KeyV}, {@code key:F3+KeyB} ({@link Keys})
     *   <li>{@code down:<code>} and {@code up:<code>}: hold a key, and let it go
     *   <li>{@code assert:<expression>}: fail unless a JavaScript expression is true (a promise's
     *       value counts)
     *   <li>{@code waitfor:<expression>}: wait up to ten seconds for an expression to be true
     *   <li>{@code holds:<expression>} and {@code until:<expression>}: {@code assert} and
     *       {@code waitfor} without counting as a click, so the page stays without user
     *       activation (the audio unlock); every other step that evaluates counts as one
     *   <li>{@code resize:<width>,<height>[,<density>]}: the viewport in CSS pixels, and the device
     *       pixels per CSS pixel (1 unless given)
     *   <li>{@code clipboard:<text>}: allow the page the clipboard, and put text on it
     *   <li>{@code compose:<text>} and {@code insert:<text>}: an input method's composition, and
     *       committed text without key events
     *   <li>{@code drop:<x>,<y>,<file>[,<file>]...}: drop files from this machine on the page
     *   <li>{@code choose:<x>,<y>,<file>[,<file>]...}: click where the page opens a file chooser, and
     *       pick files from this machine in it
     *   <li>{@code downloads:<dir>}: let the page download files, into this (emptied) folder
     *   <li>{@code download:<regex>}: wait up to 20 seconds for a download whose file name matches
     *       to finish; a {@code .png} must be a PNG
     *   <li>{@code pack:<zip>}: write a small resource pack for the check, made here from scratch
     *       (for {@code drop:} or {@code choose:}): it renames the title screen's Singleplayer
     *       button to "Singleplayer (Incari check pack)", in the pack format of the client jar the
     *       page is given
     *   <li>{@code profile:start} and {@code profile:<file>}: start the CPU profiler, and stop it,
     *       writing the profile ({@code .cpuprofile}, which Chrome's DevTools opens) to the file
     *   <li>{@code rcon:<command>}: run a command on the game server through its remote console
     *       ({@code --rcon <host:port>}, password in {@code INCARI_RCON_PASSWORD}), and print the reply
     *   <li>{@code rconwait:<command> => <regex>}: run a server command every half second until its
     *       reply contains a match, for up to a minute ({@code rconwait:list => IncariWeb})
     * </ul>
     */
    private static boolean runStep(String step, DevTools devTools, PageConsole console, long deadline, StepState state)
            throws IOException, InterruptedException {
        int colon = step.indexOf(':');
        String kind = colon < 0 ? step : step.substring(0, colon);
        String value = colon < 0 ? "" : step.substring(colon + 1);
        System.out.println("==> Step " + step);
        return switch (kind) {
            case "sleep" -> console.idle(Long.parseLong(value), deadline);
            case "wait" -> console.await(Pattern.compile(value), 1, deadline);
            case "shot" -> {
                Path file = Path.of(value);
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(file, devTools.screenshot());
                System.out.println("==> Saved " + file.toAbsolutePath());
                yield true;
            }
            case "move", "click" -> {
                String[] parts = value.split(",", -1);
                double[] viewport = devTools.viewport();
                double x = Double.parseDouble(parts[0]) * viewport[0];
                double y = Double.parseDouble(parts[1]) * viewport[1];
                if (kind.equals("click")) {
                    devTools.click(x, y);
                } else {
                    devTools.move(x, y);
                }
                yield true;
            }
            case "drag" -> {
                // drag:x1,y1,x2,y2 in fractions of the viewport, the left button held between.
                String[] parts = value.split(",", -1);
                double[] viewport = devTools.viewport();
                devTools.drag(
                        Double.parseDouble(parts[0]) * viewport[0],
                        Double.parseDouble(parts[1]) * viewport[1],
                        Double.parseDouble(parts[2]) * viewport[0],
                        Double.parseDouble(parts[3]) * viewport[1],
                        12,
                        30);
                yield true;
            }
            case "hold" -> {
                // hold:x,y,millis: the left button held down at one point (breaking a block).
                String[] parts = value.split(",", -1);
                double[] viewport = devTools.viewport();
                double x = Double.parseDouble(parts[0]) * viewport[0];
                double y = Double.parseDouble(parts[1]) * viewport[1];
                long millis = Long.parseLong(parts[2]);
                devTools.drag(x, y, x, y, (int) Math.max(1, millis / 50), 50);
                yield true;
            }
            case "type" -> {
                devTools.type(value);
                yield true;
            }
            case "init" -> {
                devTools.reloadWith(value);
                yield true;
            }
            case "reload" -> {
                devTools.reload();
                yield true;
            }
            case "eval" -> {
                System.out.println("==> " + value + " = " + devTools.evaluate(value));
                yield true;
            }
            case "key" -> {
                List<String> chord = Keys.chord(value);
                for (String code : chord) {
                    state.press(devTools, code);
                }
                for (String code : chord.reversed()) {
                    state.release(devTools, code);
                }
                yield true;
            }
            case "down" -> {
                state.press(devTools, Keys.chord(value).getFirst());
                yield true;
            }
            case "up" -> {
                state.release(devTools, Keys.chord(value).getFirst());
                yield true;
            }
            case "assert", "holds" -> {
                String result = devTools.evaluate(value, kind.equals("assert"));
                if (!result.equals("true")) {
                    System.out.println("==> Failed: " + value + " is " + result);
                    yield false;
                }
                System.out.println("==> Holds: " + value);
                yield true;
            }
            case "waitfor", "until" -> {
                boolean gesture = kind.equals("waitfor");
                long until = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                String result = devTools.evaluate(value, gesture);
                while (!result.equals("true") && System.nanoTime() < until) {
                    if (!console.idle(100, deadline)) {
                        yield false;
                    }
                    result = devTools.evaluate(value, gesture);
                }
                if (!result.equals("true")) {
                    System.out.println("==> Failed: " + value + " is still " + result);
                    yield false;
                }
                System.out.println("==> Holds: " + value);
                yield true;
            }
            case "resize" -> {
                String[] parts = value.split(",", -1);
                devTools.resize(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        parts.length > 2 ? Double.parseDouble(parts[2].trim()) : 1);
                yield true;
            }
            case "clipboard" -> {
                devTools.grantClipboard(state.origin);
                devTools.evaluate("navigator.clipboard.writeText(" + new JsonPrimitive(value) + ")");
                yield true;
            }
            case "compose" -> {
                devTools.compose(value);
                yield true;
            }
            case "insert" -> {
                devTools.insertText(value);
                yield true;
            }
            case "drop" -> {
                String[] parts = value.split(",", -1);
                double[] viewport = devTools.viewport();
                List<String> files = new ArrayList<>();
                for (int i = 2; i < parts.length; i++) {
                    files.add(Path.of(parts[i].trim()).toAbsolutePath().toString());
                }
                devTools.dropFiles(
                        Double.parseDouble(parts[0]) * viewport[0], Double.parseDouble(parts[1]) * viewport[1], files);
                yield true;
            }
            case "choose" -> {
                String[] parts = value.split(",", -1);
                double[] viewport = devTools.viewport();
                List<String> files = new ArrayList<>();
                for (int i = 2; i < parts.length; i++) {
                    files.add(Path.of(parts[i].trim()).toAbsolutePath().toString());
                }
                devTools.chooseFiles(
                        Double.parseDouble(parts[0]) * viewport[0], Double.parseDouble(parts[1]) * viewport[1], files);
                yield true;
            }
            case "downloads" -> {
                Path directory = Path.of(value).toAbsolutePath();
                if (Files.exists(directory)) {
                    Chromium.deleteRecursively(directory);
                }
                Files.createDirectories(directory);
                devTools.allowDownloads(directory.toString());
                state.downloads = directory;
                yield true;
            }
            case "download" -> {
                Path directory = state.downloads;
                if (directory == null) {
                    throw new CliException("A download: step needs a downloads: step first");
                }
                Pattern name = Pattern.compile(value);
                String file = devTools.awaitDownload(n -> name.matcher(n).matches(), 20);
                if (file == null) {
                    System.out.println("==> Failed: no download matching " + value + " finished");
                    yield false;
                }
                byte[] bytes = Files.readAllBytes(directory.resolve(file));
                if (file.endsWith(".png") && !isPng(bytes)) {
                    System.out.println("==> Failed: " + file + " isn't a PNG");
                    yield false;
                }
                System.out.println("==> Downloaded " + directory.resolve(file) + " (" + bytes.length + " bytes)");
                yield true;
            }
            case "profile" -> {
                if (value.equals("start")) {
                    devTools.startProfile();
                } else {
                    Path file = Path.of(value).toAbsolutePath();
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, devTools.stopProfile());
                    System.out.println("==> Saved " + file);
                }
                yield true;
            }
            case "pack" -> {
                Path jar = state.clientJar;
                if (jar == null) {
                    throw new CliException("A pack: step needs the client jar (--file client.jar=<path>)");
                }
                Path zip = Path.of(value).toAbsolutePath();
                CheckPack.write(zip, CheckPack.resourceFormat(jar));
                System.out.println("==> Wrote " + zip);
                yield true;
            }
            case "rcon" -> {
                System.out.println("==> " + state.rcon().command(value).strip());
                yield true;
            }
            case "rconwait" -> {
                int arrow = value.indexOf(" => ");
                if (arrow < 0) {
                    throw new CliException("rconwait needs '<command> => <regex>': " + step);
                }
                String command = value.substring(0, arrow);
                Pattern expected = Pattern.compile(value.substring(arrow + " => ".length()));
                long until = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
                String reply = state.rcon().command(command).strip();
                while (!expected.matcher(reply).find() && System.nanoTime() < until) {
                    if (!console.idle(500, deadline)) {
                        yield false;
                    }
                    reply = state.rcon().command(command).strip();
                }
                if (!expected.matcher(reply).find()) {
                    System.out.println("==> Failed: the server still answers '" + reply + "'");
                    yield false;
                }
                System.out.println("==> Holds: " + reply);
                yield true;
            }
            default -> throw new CliException("Unknown step '" + step + "'");
        };
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        return bytes.length > signature.length
                && Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length);
    }

    /** What steps leave behind for later steps: the keys held, the page's origin, the server's console. */
    private static final class StepState implements AutoCloseable {
        final String origin;

        @Nullable
        Path downloads;

        @Nullable
        Path clientJar;

        private final List<String> held = new ArrayList<>();
        private final @Nullable String rconAddress;
        private @Nullable Rcon rcon;

        StepState(String url, @Nullable String rconAddress) {
            URI uri = URI.create(url);
            this.origin = uri.getScheme() + "://" + uri.getAuthority();
            this.rconAddress = rconAddress;
        }

        /** The server's remote console, logged in on first use. */
        Rcon rcon() throws IOException {
            Rcon open = rcon;
            if (open == null) {
                if (rconAddress == null) {
                    throw new CliException("An rcon: step needs --rcon <host:port> (-Pbrowser.rcon=...)");
                }
                String password = System.getenv("INCARI_RCON_PASSWORD");
                if (password == null || password.isEmpty()) {
                    throw new CliException("An rcon: step needs the server's rcon.password in INCARI_RCON_PASSWORD");
                }
                open = Rcon.connect(rconAddress, password);
                rcon = open;
            }
            return open;
        }

        @Override
        public void close() throws IOException {
            Rcon open = rcon;
            if (open != null) {
                open.close();
            }
        }

        void press(DevTools devTools, String code) throws IOException, InterruptedException {
            held.add(code);
            devTools.key(code, true, modifiers());
        }

        void release(DevTools devTools, String code) throws IOException, InterruptedException {
            held.remove(code);
            devTools.key(code, false, modifiers());
        }

        private int modifiers() {
            int bits = 0;
            for (String code : held) {
                bits |= Keys.modifierBit(code);
            }
            return bits;
        }
    }

    /** A steps file: one step per line; blank lines and lines starting with # are skipped. */
    private static List<String> readSteps(Path file) throws IOException {
        List<String> steps = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String step = line.strip();
            if (!step.isEmpty() && !step.startsWith("#")) {
                steps.add(step);
            }
        }
        return steps;
    }
}
