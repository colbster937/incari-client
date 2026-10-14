package incari.tools.webbuild;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * The browser build over WebGL2 in headless Chromium, driven through the DevTools protocol: served
 * like {@code browserCheck} serves it, with the pinned options handed to the page
 * ({@code ?options=}), straight into the harness's server over the WebSocket bridge, in a viewport
 * of exactly the harness's size at one device pixel per CSS pixel, so the canvas matches the
 * desktop window pixel for pixel.
 */
final class BrowserSceneClient implements SceneClient {

    private final HttpServer server;
    private final Chromium chromium;
    private final DevTools devTools;
    private final List<String> held = new ArrayList<>();
    private final long deadline;

    private BrowserSceneClient(HttpServer server, Chromium chromium, DevTools devTools, long deadline) {
        this.server = server;
        this.chromium = chromium;
        this.devTools = devTools;
        this.deadline = deadline;
    }

    /**
     * Opens the page.
     *
     * @param files what the page fetches from beside it: the client jar, the shaders, the options
     * @param query the page's options ({@code server=...&name=...&options=...})
     */
    static BrowserSceneClient start(
            Path build,
            Map<String, Path> files,
            String query,
            @Nullable String chrome,
            Chromium.Gpu gpu,
            int httpPort,
            int devToolsPort,
            int width,
            int height)
            throws IOException, InterruptedException {
        HttpServer server = Serve.start(build, httpPort, files);
        String url = "http://localhost:" + server.getAddress().getPort() + "/?" + PageQuery.withBuild(query);
        Chromium chromium = Chromium.open(chrome, url, gpu, devToolsPort, width + "," + height);
        long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
        try {
            PageConsole console = chromium.console();
            if (!console.await(Pattern.compile("\\[Incari\\] Main loop running.*"), 1, deadline)) {
                throw new IOException("The browser build didn't start");
            }
            DevTools devTools = DevTools.connect(console.devToolsUrl(deadline));
            devTools.resize(width, height, 1);
            // The development page's status line would sit on top of the canvas in every screenshot.
            devTools.evaluate("document.getElementById('incari-status')?.remove(), true");
            return new BrowserSceneClient(server, chromium, devTools, deadline);
        } catch (IOException | RuntimeException e) {
            chromium.close();
            server.stop(0);
            throw e;
        }
    }

    @Override
    public String name() {
        return "browser";
    }

    /** The page's console so far, to check for WebGL errors and skipped pipelines. */
    PageConsole console() {
        return chromium.console();
    }

    /** A JavaScript expression's value in the page, as JSON. */
    String evaluate(String expression) throws IOException, InterruptedException {
        return devTools.evaluate(expression);
    }

    @Override
    public void awaitWorld() throws IOException, InterruptedException {
        // The server lists the player once the login finishes; the world's first frames follow.
        if (!chromium.console().idle(12_000, deadline)) {
            throw new IOException("The browser build stopped");
        }
    }

    @Override
    public void key(List<String> chord) throws IOException, InterruptedException {
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

    @Override
    public void move(double x, double y) throws IOException, InterruptedException {
        double[] viewport = devTools.viewport();
        devTools.move(x * viewport[0], y * viewport[1]);
    }

    @Override
    public void click(double x, double y) throws IOException, InterruptedException {
        double[] viewport = devTools.viewport();
        devTools.click(x * viewport[0], y * viewport[1]);
    }

    @Override
    public BufferedImage shot(Path file) throws IOException, InterruptedException {
        byte[] png = devTools.screenshot();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, png);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("The browser's screenshot isn't a PNG");
        }
        return image;
    }

    @Override
    public void close() throws IOException {
        devTools.close();
        chromium.close();
        server.stop(0);
    }
}
