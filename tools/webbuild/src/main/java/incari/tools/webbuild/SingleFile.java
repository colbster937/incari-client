package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Packs a web folder ({@link WebFolder}) into one HTML file that plays from the disk (ADR
 * 0057): every file the page fetches goes in as base64, gzipped where that's smaller, and the page's
 * {@code offline.js} inline starts it, serving those files to the page and its workers by their
 * usual names. The service worker and its cache policy stay out, since a page opened from the disk
 * can't have one.
 *
 * <p>Run by {@code ./gradlew :app-web:singleFile}, which {@code ./incari build --html} calls after
 * writing the folder. The file holds Mojang's code and files: never committed, shared or published.
 */
public final class SingleFile {

    /** Starts every file this tool writes, so a rerun may replace it and nothing else is overwritten. */
    static final String SIGNATURE = "<!-- Incari single-file build, written by ./incari build --html.";

    private static final String NOTICE = SIGNATURE + """

            DO NOT SHARE, UPLOAD OR PUBLISH THIS FILE.
            It contains Minecraft's code and files, which Mojang doesn't allow redistributing. The Incari
            project never publishes builds and gives nobody permission to. It is for your own use, on
            your own machine; see docs/self-hosting.md. Incari is not affiliated with or endorsed by
            Mojang or Microsoft. -->
            """;

    /** The line in {@code index.html} the inline loader replaces. */
    private static final String BOOT = "<script type=\"module\" src=\"boot.js\"></script>";

    /** The folder's files that don't go in: the server's, and the service worker's. */
    private static final List<String> LEFT_OUT =
            List.of("index.html", ".htaccess", "README.txt", WebFolder.MARKER, "sw.js", "cache-policy.js");

    /** The page's scripts first (decoded before the rest), then small to large. */
    private static final Comparator<Path> ORDER = Comparator.<Path, Boolean>comparing(
                    path -> !path.toString().endsWith(".js"))
            .thenComparingLong(SingleFile::size);

    private SingleFile() {}

    record Options(Path folder, Path out, Path script) {}

    record Summary(int files, long bytes) {}

    public static void main(String[] args) throws IOException {
        Options options = parse(args);
        Summary summary = write(options);
        System.out.println("==> Wrote " + options.out() + ": " + summary.files() + " files in one page, "
                + summary.bytes() / (1024 * 1024) + " MB");
    }

    static Options parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (!name.startsWith("--") || i + 1 >= args.length) {
                throw new CliException("Unexpected argument '" + name + "'");
            }
            values.put(name.substring(2), args[++i]);
        }
        for (String name : values.keySet()) {
            if (!List.of("folder", "out", "script").contains(name)) {
                throw new CliException("Unknown option '--" + name + "'");
            }
        }
        return new Options(
                Path.of(required(values, "folder")),
                Path.of(required(values, "out")),
                Path.of(required(values, "script")));
    }

    static Summary write(Options options) throws IOException {
        Path folder = options.folder().toAbsolutePath().normalize();
        Path out = options.out().toAbsolutePath().normalize();
        if (!Files.isRegularFile(folder.resolve(WebFolder.MARKER))) {
            throw new CliException(folder + " isn't a folder ./incari build wrote");
        }
        requireReplaceable(out);
        String page = Files.readString(folder.resolve("index.html"));
        if (!page.contains(BOOT)) {
            throw new CliException("index.html has no " + BOOT + " for the inline loader to replace");
        }
        String script = Files.readString(options.script());
        if (script.toLowerCase(Locale.ROOT).contains("</script")) {
            throw new CliException(options.script() + " can't go inline: it contains </script");
        }
        int head = page.indexOf('\n') + 1;

        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> !LEFT_OUT.contains(folder.relativize(file).toString()))
                    .sorted(ORDER)
                    .toList();
        }
        Files.createDirectories(out.getParent());
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        try (OutputStream stream = Files.newOutputStream(partial);
                Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(page.substring(0, head));
            writer.write(NOTICE);
            String body = page.substring(head);
            int boot = body.indexOf(BOOT);
            writer.write(body, 0, boot);
            writer.write("<script type=\"module\">\n");
            writer.write(script);
            writer.write("\nstart(window);\n</script>\n");
            for (Path file : files) {
                String path = folder.relativize(file).toString().replace('\\', '/');
                byte[] bytes = Files.readAllBytes(file);
                byte[] packed = gzip(bytes);
                boolean gzipped = packed.length < bytes.length * 9L / 10;
                writer.write("<script type=\"application/x-incari-file\" data-path=\"" + attribute(path)
                        + "\" data-type=\"" + type(path) + "\"" + (gzipped ? " data-encoding=\"gzip\"" : "") + ">");
                writer.flush();
                // Straight into the file: the largest is tens of megabytes.
                OutputStream base64 = Base64.getEncoder().wrap(new FilterOutputStream(stream) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                });
                base64.write(gzipped ? packed : bytes);
                base64.close();
                writer.write("</script>\n");
            }
            writer.write(body, boot + BOOT.length(), body.length() - boot - BOOT.length());
        }
        Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Summary(files.size(), Files.size(out));
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(bytes.length / 2);
        try (GZIPOutputStream gzip = new GZIPOutputStream(packed) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            gzip.write(bytes);
        }
        return packed.toByteArray();
    }

    /** The media type the file's Blob gets: the browser compiles {@code application/wasm} as it reads. */
    static String type(String path) {
        if (path.endsWith(".js")) {
            return "text/javascript";
        }
        if (path.endsWith(".wasm")) {
            return "application/wasm";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        if (path.endsWith(".jar")) {
            return "application/java-archive";
        }
        return "application/octet-stream";
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /** Refuses to overwrite a file this tool didn't write. */
    static void requireReplaceable(Path out) throws IOException {
        if (!Files.exists(out)) {
            return;
        }
        if (!Files.isRegularFile(out)) {
            throw new CliException(out + " exists and isn't a file");
        }
        byte[] start = new byte[512];
        int read;
        try (InputStream in = Files.newInputStream(out)) {
            read = in.readNBytes(start, 0, start.length);
        }
        if (!new String(start, 0, read, StandardCharsets.UTF_8).contains(SIGNATURE)) {
            throw new CliException(out + " already exists and ./incari build didn't write it. Pick another name");
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) {
            throw new CliException("Missing --" + name);
        }
        return value;
    }
}
