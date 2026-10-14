package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Headless Chromium for the browser checks: found, started on a page with a profile of its own,
 * its console read ({@link PageConsole}), and cleaned up.
 *
 * <p>Chromium comes from {@code --chrome}, else {@code INCARI_CHROME}, else Playwright's download
 * cache, else {@code chromium} or {@code google-chrome} on the path.
 */
final class Chromium implements AutoCloseable {

    /**
     * Which GPU WebGL runs on. Headless Chromium picks SwiftShader, its software renderer, unless
     * told to use the machine's GPU through ANGLE.
     */
    enum Gpu {
        /** SwiftShader: works anywhere (CI, machines without a GPU), slowly, and differs from any real GPU. */
        SWIFTSHADER("--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"),
        /** The machine's GPU through ANGLE's OpenGL backend: the same driver the desktop game uses. */
        GL("--use-angle=gl", "--ignore-gpu-blocklist"),
        /** The machine's GPU through ANGLE's Vulkan backend, which Chrome prefers on some systems. */
        VULKAN("--use-angle=vulkan", "--enable-features=Vulkan", "--ignore-gpu-blocklist"),
        /**
         * As {@link #VULKAN}, with WebGPU on the machine's GPU: on Linux, Chrome's WebGPU needs Vulkan,
         * and headless Chromium offers only SwiftShader's adapter without {@code --enable-unsafe-webgpu}
         * (docs/webgpu-backend.md).
         */
        WEBGPU("--use-angle=vulkan", "--enable-features=Vulkan", "--enable-unsafe-webgpu", "--ignore-gpu-blocklist");

        /** Chromium flags, separated by spaces (an enum holds no mutable list). */
        private final String flags;

        Gpu(String... flags) {
            this.flags = String.join(" ", flags);
        }

        static Gpu parse(String name) {
            return switch (name) {
                case "swiftshader" -> SWIFTSHADER;
                case "gl" -> GL;
                case "vulkan" -> VULKAN;
                case "webgpu" -> WEBGPU;
                default -> throw new CliException("Unknown GPU '" + name + "' (swiftshader, gl, vulkan or webgpu)");
            };
        }
    }

    private final Process process;
    private final Path profile;
    private final PageConsole console;

    private Chromium(Process process, Path profile) {
        this.process = process;
        this.profile = profile;
        this.console = new PageConsole(process);
    }

    /**
     * Opens {@code url} in headless Chromium.
     *
     * @param devToolsPort where to listen for the DevTools protocol (steps, screenshots): 0 for any
     *     free port, -1 not at all
     * @param windowSize the window's size, {@code 1280,720} say, or null for Chromium's default
     */
    static Chromium open(@Nullable String chrome, String url, Gpu gpu, int devToolsPort, @Nullable String windowSize)
            throws IOException {
        return open(chrome, url, gpu, devToolsPort, windowSize, true, List.of());
    }

    /**
     * Opens {@code url} in Chromium, headless or in a window of its own (the benchmark runner's
     * {@code --headed}: frame pacing in a real window is what players get), with
     * {@code extraFlags} added to the usual ones.
     */
    static Chromium open(
            @Nullable String chrome,
            String url,
            Gpu gpu,
            int devToolsPort,
            @Nullable String windowSize,
            boolean headless,
            List<String> extraFlags)
            throws IOException {
        Path browser = chrome != null ? Path.of(chrome) : find();
        Path profile = Files.createTempDirectory("incari-browser-check");
        System.out.println("==> Opening " + url + " in " + browser);
        List<String> command = new ArrayList<>(List.of(browser.toString()));
        if (headless) {
            command.add("--headless=new");
        }
        command.addAll(List.of(
                "--no-first-run",
                "--no-default-browser-check",
                // As a player's browser does: sound waits for a click or a key press (the
                // unlock prompt). Headless Chromium would otherwise let the page play at once, and
                // does even with user-gesture-required.
                "--autoplay-policy=document-user-activation-required",
                // Silent on this machine's speakers; the page's audio graph still runs, and checks
                // measure it there (browser-checks/audio*.steps).
                "--mute-audio",
                // Voice chat: a fake microphone (a steady tone), and the permission prompt
                // answered yes, as a player would.
                "--use-fake-device-for-media-stream",
                "--use-fake-ui-for-media-stream",
                "--user-data-dir=" + profile,
                "--enable-logging=stderr",
                "--v=0"));
        command.addAll(List.of(gpu.flags.split(" ")));
        if (devToolsPort >= 0) {
            command.add("--remote-debugging-port=" + devToolsPort);
        }
        if (windowSize != null) {
            command.add("--window-size=" + windowSize);
        }
        command.addAll(extraFlags);
        command.add(url);
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return new Chromium(process, profile);
    }

    PageConsole console() {
        return console;
    }

    @Override
    public void close() throws IOException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteRecursively(profile);
    }

    private static Path find() throws IOException {
        String fromEnvironment = System.getenv("INCARI_CHROME");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Path.of(fromEnvironment);
        }
        Path playwright = Path.of(System.getProperty("user.home"), ".cache", "ms-playwright");
        if (Files.isDirectory(playwright)) {
            try (Stream<Path> found = Files.walk(playwright, 3)) {
                List<Path> candidates = new ArrayList<>(
                        found.filter(p -> p.getFileName().toString().equals("chrome") && Files.isExecutable(p))
                                .sorted()
                                .toList());
                if (!candidates.isEmpty()) {
                    return candidates.getLast();
                }
            }
        }
        for (String name : List.of("chromium", "chromium-browser", "google-chrome")) {
            for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator, -1)) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new CliException("No Chromium found; pass --chrome <path> or set INCARI_CHROME");
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
