package incari.tools.webbuild;

import incari.tools.build.CliException;
import incari.tools.webbuild.DownloadBudget.Kind;
import incari.tools.webbuild.DownloadBudget.Measure;
import incari.tools.webbuild.DownloadBudget.Measured;
import incari.tools.webbuild.DownloadBudget.Outcome;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Measures what a player downloads: every file in the build output and each extra file
 * the page fetches, raw and compressed, held against {@code app-web/download-budget.properties}.
 * Fails when a file is over budget or isn't classified. Run by {@code ./gradlew
 * :app-web:checkDownloadSize}.
 *
 * <p>Compressed sizes are what a static host serves from files compressed once at deploy time,
 * at the formats' highest levels: gzip level 9 and brotli quality 11. Every browser takes gzip
 * and every static host can serve it, so the budget is in gzip; brotli (smaller, but not on every
 * host) is reported alongside.
 */
public final class DownloadSize {

    static final int GZIP_LEVEL = 9;
    static final int BROTLI_QUALITY = 11;

    private DownloadSize() {}

    public static void main(String[] args) {
        int status;
        try {
            status = run(Options.parse(List.of(args)));
        } catch (CliException e) {
            System.err.println("error: " + e.getMessage());
            status = 1;
        } catch (Exception e) {
            e.printStackTrace();
            status = 2;
        }
        System.exit(status);
    }

    static int run(Options options) throws Exception {
        DownloadBudget budget = DownloadBudget.load(options.budget());
        if (!Files.isDirectory(options.buildDirectory())) {
            throw new CliException(options.buildDirectory() + " doesn't exist; build it with ./gradlew :app-web:teavm");
        }
        Map<String, Path> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(options.buildDirectory())) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> files.put(
                            options.buildDirectory().relativize(file).toString().replace(File.separatorChar, '/'),
                            file));
        }
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Path> extra : options.extras().entrySet()) {
            if (files.containsKey(extra.getKey())) {
                throw new CliException(
                        extra.getKey() + " is both in " + options.buildDirectory() + " and an extra file");
            }
            if (Files.isRegularFile(extra.getValue())) {
                files.put(extra.getKey(), extra.getValue());
            } else if (budget.limits().stream().noneMatch(l -> l.file().equals(extra.getKey()))) {
                // A missing file with a limit gets its note from the verdict.
                notes.add(extra.getKey() + " isn't built yet (no " + extra.getValue() + ")");
            }
        }

        List<String> unclassified = new ArrayList<>();
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (String name : files.keySet()) {
            Kind kind = budget.kindOf(name);
            if (kind == null) {
                unclassified.add(name);
            } else {
                kinds.put(name, kind);
            }
        }
        if (!unclassified.isEmpty()) {
            throw new CliException("Not classified in " + options.budget() + ": " + String.join(", ", unclassified)
                    + ". Add a file.<name>=download|debug|report|vendor line (and a limit if players download it).");
        }

        @Nullable Path brotli = options.brotli() ? findBrotli() : null;
        if (options.brotli() && brotli == null) {
            notes.add("no brotli command (set INCARI_BROTLI or put brotli on the PATH); brotli sizes not measured");
        }
        System.out.println("==> Measuring " + files.size() + " files (gzip -" + GZIP_LEVEL
                + (brotli != null
                        ? ", brotli -q " + BROTLI_QUALITY + ", which takes a minute or two on a big module"
                        : "")
                + ")");
        List<Measured> measured = measure(files, kinds, brotli);

        DownloadBudget.Verdict verdict = budget.judge(measured, options.extras().keySet());
        System.out.print(table(measured, budget));
        for (Outcome outcome : verdict.outcomes()) {
            if (outcome.limit().measure() != Measure.GZIP) {
                System.out.printf(
                        Locale.ROOT,
                        "    %s %s: %s of %s%n",
                        outcome.limit().measure().label(),
                        outcome.limit().file(),
                        human(outcome.actual()),
                        human(outcome.limit().bytes()));
            }
        }
        notes.addAll(verdict.notes());
        for (String note : notes) {
            System.out.println("note: " + note);
        }
        if (options.report() != null) {
            Files.createDirectories(options.report().toAbsolutePath().getParent());
            Files.writeString(options.report(), json(measured, verdict));
            System.out.println("==> Report: " + options.report());
        }
        if (!verdict.passed()) {
            for (String failure : verdict.failures()) {
                System.err.println("Download size: " + failure);
            }
            System.err.println("Make the build smaller, or raise the limit in " + options.budget()
                    + " in this pull request and say why.");
            return 1;
        }
        return 0;
    }

    private static List<Measured> measure(Map<String, Path> files, Map<String, Kind> kinds, @Nullable Path brotli)
            throws InterruptedException, ExecutionException, IOException {
        record Pending(
                String name,
                Kind kind,
                long raw,
                Future<Long> gzip,
                @Nullable Future<Long> brotli) {}
        // Brotli's highest quality is slow (minutes on a development build's module) and
        // single-threaded, so the files are compressed side by side, the biggest first.
        List<Map.Entry<String, Path>> bySize = new ArrayList<>(files.entrySet());
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : bySize) {
            sizes.put(entry.getKey(), Files.size(entry.getValue()));
        }
        bySize.sort(Comparator.comparingLong((Map.Entry<String, Path> e) -> sizes.getOrDefault(e.getKey(), 0L))
                .reversed());
        ExecutorService pool =
                Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            List<Pending> pending = new ArrayList<>();
            for (Map.Entry<String, Path> entry : bySize) {
                Path file = entry.getValue();
                Future<Long> br = brotli == null ? null : pool.submit(() -> brotliSize(brotli, file));
                pending.add(new Pending(
                        entry.getKey(),
                        Objects.requireNonNull(kinds.get(entry.getKey())),
                        sizes.getOrDefault(entry.getKey(), 0L),
                        pool.submit(() -> gzipSize(file)),
                        br));
            }
            List<Measured> measured = new ArrayList<>();
            for (Pending p : pending) {
                Future<Long> br = p.brotli();
                measured.add(new Measured(p.name(), p.kind(), p.raw(), p.gzip().get(), br == null ? null : br.get()));
            }
            measured.sort(Comparator.comparing(Measured::name));
            return measured;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The file's size gzipped at {@link #GZIP_LEVEL}, as {@code gzip -9} makes it. */
    static long gzipSize(Path file) throws IOException {
        CountingStream count = new CountingStream();
        try (InputStream in = Files.newInputStream(file);
                GZIPOutputStream out = new LevelGzipStream(count, GZIP_LEVEL)) {
            in.transferTo(out);
        }
        return count.bytes;
    }

    /** The file's size compressed by the brotli command at {@link #BROTLI_QUALITY}. */
    static long brotliSize(Path brotli, Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                        brotli.toString(), "-q", String.valueOf(BROTLI_QUALITY), "-c", "--", file.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        process.getOutputStream().close();
        long bytes;
        try (InputStream out = process.getInputStream()) {
            bytes = out.transferTo(OutputStream.nullOutputStream());
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new CliException(brotli + " failed on " + file + " (exit " + exit + ")");
        }
        return bytes;
    }

    /**
     * The brotli command: {@code INCARI_BROTLI}, else {@code brotli} on the PATH. Java has no
     * brotli encoder without a native library (org.brotli:dec only decodes; brotli4j bundles
     * natives), and the command is one package on every Linux distribution.
     */
    static @Nullable Path findBrotli() {
        String configured = System.getenv("INCARI_BROTLI");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!Files.isExecutable(path)) {
                throw new CliException("INCARI_BROTLI is " + configured + ", which isn't an executable");
            }
            return path;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator, -1)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, "brotli");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The console table: every file with its class and sizes, and the gzip budget. */
    static String table(List<Measured> files, DownloadBudget budget) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"file", "class", "raw", "gzip", "brotli", "budget (gzip)", "headroom"});
        List<Measured> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(Measured::kind).thenComparing(Measured::name));
        for (Measured file : sorted) {
            rows.add(row(file.name(), file.kind().label(), file.raw(), file.gzip(), file.size(Measure.BROTLI), budget));
        }
        rows.add(row(
                "total downloaded",
                "download",
                DownloadBudget.total(files, Measure.RAW),
                DownloadBudget.total(files, Measure.GZIP),
                DownloadBudget.total(files, Measure.BROTLI),
                budget,
                DownloadBudget.TOTAL));
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r == rows.size() - 1) {
                out.append("  ")
                        .append("-".repeat(sum(widths) + 2 * (widths.length - 1)))
                        .append('\n');
            }
            String[] row = rows.get(r);
            out.append("  ");
            for (int i = 0; i < row.length; i++) {
                // Names and classes left-aligned, numbers right-aligned.
                String cell = i < 2 ? pad(row[i], widths[i], false) : pad(row[i], widths[i], true);
                out.append(cell);
                if (i < row.length - 1) {
                    out.append("  ");
                }
            }
            out.append('\n');
        }
        return out.toString().replaceAll(" +\n", "\n");
    }

    private static String[] row(String name, String kind, long raw, long gzip, long brotli, DownloadBudget budget) {
        return row(name, kind, raw, gzip, brotli, budget, name);
    }

    private static String[] row(
            String name, String kind, long raw, long gzip, long brotli, DownloadBudget budget, String limitName) {
        DownloadBudget.Limit limit = budget.limit(Measure.GZIP, limitName);
        String budgetCell = limit == null ? "" : human(limit.bytes());
        String headroom = "";
        if (limit != null) {
            long left = limit.bytes() - gzip;
            headroom = (left < 0 ? "OVER " : "")
                    + human(Math.abs(left))
                    + String.format(Locale.ROOT, " (%.0f%%)", 100.0 * left / Math.max(1, limit.bytes()));
        }
        return new String[] {
            name, kind, human(raw), human(gzip), brotli < 0 ? "n/a" : human(brotli), budgetCell, headroom
        };
    }

    private static int sum(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        return sum;
    }

    private static String pad(String text, int width, boolean right) {
        String padding = " ".repeat(width - text.length());
        return right ? padding + text : text + padding;
    }

    /** Decimal units, as download sizes are quoted. */
    static String human(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        if (bytes < 1000) {
            return bytes + " B";
        }
        if (bytes < 1_000_000) {
            return String.format(Locale.ROOT, "%.1f kB", bytes / 1e3);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / 1e6);
    }

    /** The machine-readable report, for charting the sizes over time. */
    static String json(List<Measured> files, DownloadBudget.Verdict verdict) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"gzipLevel\": ")
                .append(GZIP_LEVEL)
                .append(",\n  \"brotliQuality\": ")
                .append(BROTLI_QUALITY);
        out.append(",\n  \"files\": [");
        for (int i = 0; i < files.size(); i++) {
            Measured file = files.get(i);
            out.append(i == 0 ? "\n" : ",\n")
                    .append("    {\"name\": ")
                    .append(quote(file.name()))
                    .append(", \"class\": ")
                    .append(quote(file.kind().label()))
                    .append(", \"raw\": ")
                    .append(file.raw())
                    .append(", \"gzip\": ")
                    .append(file.gzip())
                    .append(", \"brotli\": ")
                    .append(file.brotli() == null ? "null" : String.valueOf(file.brotli()))
                    .append('}');
        }
        out.append("\n  ],\n  \"totalDownloaded\": {\"raw\": ")
                .append(DownloadBudget.total(files, Measure.RAW))
                .append(", \"gzip\": ")
                .append(DownloadBudget.total(files, Measure.GZIP))
                .append(", \"brotli\": ");
        long brotli = DownloadBudget.total(files, Measure.BROTLI);
        out.append(brotli < 0 ? "null" : String.valueOf(brotli)).append("},\n  \"limits\": [");
        List<Outcome> outcomes = verdict.outcomes();
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome outcome = outcomes.get(i);
            out.append(i == 0 ? "\n" : ",\n")
                    .append("    {\"measure\": ")
                    .append(quote(outcome.limit().measure().label()))
                    .append(", \"file\": ")
                    .append(quote(outcome.limit().file()))
                    .append(", \"limit\": ")
                    .append(outcome.limit().bytes())
                    .append(", \"actual\": ")
                    .append(outcome.actual())
                    .append('}');
        }
        out.append("\n  ],\n  \"passed\": ").append(verdict.passed()).append("\n}\n");
        return out.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Counts what's written, so nothing compressed is kept in memory. */
    private static final class CountingStream extends OutputStream {
        long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }

    /** {@link GZIPOutputStream} at a chosen level; it only exposes its deflater to subclasses. */
    private static final class LevelGzipStream extends GZIPOutputStream {
        LevelGzipStream(OutputStream out, int level) throws IOException {
            super(out, 1 << 16);
            def.setLevel(level);
        }
    }

    /** Command-line options; Gradle passes them. */
    record Options(
            Path budget,
            Path buildDirectory,
            Map<String, Path> extras,
            @Nullable Path report,
            boolean brotli) {

        static Options parse(List<String> args) {
            Path budget = null;
            Path directory = null;
            Map<String, Path> extras = new LinkedHashMap<>();
            Path report = null;
            boolean brotli = true;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "--budget" -> budget = Path.of(value(args, ++i, arg));
                    case "--dir" -> directory = Path.of(value(args, ++i, arg));
                    case "--file" -> {
                        String spec = value(args, ++i, arg);
                        int eq = spec.indexOf('=');
                        if (eq <= 0 || eq == spec.length() - 1) {
                            throw new CliException("--file takes <name>=<path>, found '" + spec + "'");
                        }
                        if (extras.put(spec.substring(0, eq), Path.of(spec.substring(eq + 1))) != null) {
                            throw new CliException("--file " + spec.substring(0, eq) + " given twice");
                        }
                    }
                    case "--report" -> report = Path.of(value(args, ++i, arg));
                    case "--no-brotli" -> brotli = false;
                    default -> throw new CliException("Unknown option '" + arg + "'");
                }
            }
            if (budget == null || directory == null) {
                throw new CliException("Usage: DownloadSize --budget <properties> --dir <build output>"
                        + " [--file <name>=<path>]... [--report <json>] [--no-brotli]");
            }
            return new Options(budget, directory, Collections.unmodifiableMap(extras), report, brotli);
        }

        private static String value(List<String> args, int index, String option) {
            if (index >= args.size()) {
                throw new CliException(option + " needs a value");
            }
            return args.get(index);
        }
    }
}
