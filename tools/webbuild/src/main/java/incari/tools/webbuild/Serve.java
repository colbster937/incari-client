package incari.tools.webbuild;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Serves the browser build's output directory on localhost, for development only. Serves
 * {@code .wasm} as {@code application/wasm} (streaming compilation requires it) and sends the
 * cross-origin isolation headers the build will need for shared memory and workers. Files from
 * elsewhere are served by name beside the build: the client jar at {@code /client.jar}, where the
 * page fetches the game's resources, and the translated shaders at {@code /shaders.json}. Whole
 * directories can be mounted under a prefix the same way: the asset objects {@code ./incari assets}
 * downloaded, which the page fetches as sounds play.
 *
 * <p>Binds to the loopback address only: builds contain Mojang code and are never published.
 */
public final class Serve {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "wasm", "application/wasm",
            "json", "application/json",
            "jar", "application/java-archive",
            "map", "application/json",
            "teadbg", "application/octet-stream");

    private Serve() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println(
                    "Usage: serve <directory> [--port <n>] [--file <name>=<path>]... [--dir <prefix>=<path>]...");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int port = 8080;
        Map<String, Path> files = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--file" -> addFile(files, args[++i]);
                case "--dir" -> addDirectory(files, args[++i]);
                default -> {
                    System.err.println("Unknown option '" + args[i] + "'");
                    System.exit(2);
                }
            }
        }
        HttpServer server = start(root, port, files);
        System.out.println("Serving " + root + " at http://localhost:"
                + server.getAddress().getPort() + "/ (Ctrl+C to stop)");
    }

    /** Adds a {@code <name>=<path>} option's file, served at {@code /<name>}. */
    static void addFile(Map<String, Path> files, String option) {
        int equals = option.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException("Expected <name>=<path>, got '" + option + "'");
        }
        files.put("/" + option.substring(0, equals), Path.of(option.substring(equals + 1)));
    }

    /**
     * Adds a {@code <prefix>=<path>} option's directory: {@code /<prefix>/<file>} serves
     * {@code <path>/<file>}. Kept with the named files, under a key ending in a slash.
     */
    static void addDirectory(Map<String, Path> files, String option) {
        int equals = option.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException("Expected <prefix>=<path>, got '" + option + "'");
        }
        String prefix = option.substring(0, equals);
        files.put("/" + (prefix.endsWith("/") ? prefix : prefix + "/"), Path.of(option.substring(equals + 1)));
    }

    /**
     * Starts serving {@code root} on the loopback address, and each of {@code files} (URL path to
     * file) by its one name; port 0 picks a free one.
     */
    static HttpServer start(Path root, int port, Map<String, Path> files) throws IOException {
        return start(root, InetAddress.getLoopbackAddress(), port, files);
    }

    /**
     * Serves on {@code address}: the loopback interface unless another machine must reach the page
     * (the benchmark runner on a Chromebook). Never on a public network: every build contains
     * Mojang code and must not be published.
     */
    static HttpServer start(Path root, InetAddress address, int port, Map<String, Path> files) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        Map<String, Path> named = Map.copyOf(files);
        HttpServer server = HttpServer.create(new InetSocketAddress(address, port), 0);
        server.createContext("/", exchange -> handle(directory, named, exchange));
        server.start();
        return server;
    }

    /** The file a request names under a mounted directory, or null; never outside that directory. */
    static @Nullable Path mounted(Map<String, Path> files, String path) {
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String prefix = entry.getKey();
            if (prefix.endsWith("/") && path.startsWith(prefix)) {
                Path directory = entry.getValue().toAbsolutePath().normalize();
                Path file = directory.resolve(path.substring(prefix.length())).normalize();
                return file.startsWith(directory) ? file : null;
            }
        }
        return null;
    }

    private static void handle(Path root, Map<String, Path> files, HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/")) {
                path += "index.html";
            }
            Path file = root.resolve(path.substring(1)).normalize();
            // Only files under the root, and the named files by their one name each.
            boolean allowed = file.startsWith(root);
            Path named = files.get(path);
            if (named != null) {
                file = named;
                allowed = true;
            } else {
                Path mounted = mounted(files, path);
                if (mounted != null) {
                    file = mounted;
                    allowed = true;
                }
            }
            if (!allowed || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String name = file.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            exchange.getResponseHeaders()
                    .set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
            exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
            exchange.sendResponseHeaders(200, Files.size(file));
            try (OutputStream body = exchange.getResponseBody()) {
                Files.copy(file, body);
            }
        }
    }
}
