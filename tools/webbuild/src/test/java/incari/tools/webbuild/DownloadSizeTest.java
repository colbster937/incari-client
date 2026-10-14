package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadSizeTest {

    @TempDir
    Path dir;

    @Test
    void gzipSizeIsARealGzipStreamsSize() throws Exception {
        byte[] data =
                "the quick brown fox jumps over the lazy dog\n".repeat(1000).getBytes(StandardCharsets.UTF_8);
        Path file = dir.resolve("data.txt");
        Files.write(file, data);
        long size = DownloadSize.gzipSize(file);
        assertThat(size).isGreaterThan(18).isLessThan(data.length / 10);

        // The same bytes, written out, decompress to the input.
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (var out = new java.util.zip.GZIPOutputStream(compressed) {
            {
                def.setLevel(DownloadSize.GZIP_LEVEL);
            }
        }) {
            out.write(data);
        }
        assertThat(compressed.size()).isEqualTo(size);
        try (var in = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed.toByteArray()))) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void checksADirectoryAndExtraFilesAndWritesAReport() throws Exception {
        Path build = Files.createDirectories(dir.resolve("build"));
        Files.writeString(build.resolve("game.wasm"), "x".repeat(10_000));
        Files.writeString(build.resolve("problems.txt"), "");
        Path extra = dir.resolve("elsewhere/extra.json");
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "{}");
        Path budget = dir.resolve("budget.properties");
        Files.write(
                budget,
                List.of(
                        "file.game.wasm=download",
                        "file.extra.json=download",
                        "file.later.json=download",
                        "file.*.txt=report",
                        "max.gzip.total=1000",
                        "max.raw.game.wasm=10000",
                        "max.gzip.later.json=10"));
        Path report = dir.resolve("reports/size.json");

        int status = DownloadSize.run(DownloadSize.Options.parse(List.of(
                "--budget",
                budget.toString(),
                "--dir",
                build.toString(),
                "--file",
                "extra.json=" + extra,
                "--file",
                "later.json=" + dir.resolve("missing.json"),
                "--report",
                report.toString(),
                "--no-brotli")));

        assertThat(status).isZero();
        String json = Files.readString(report);
        assertThat(json)
                .contains("{\"name\": \"game.wasm\", \"class\": \"download\", \"raw\": 10000, \"gzip\": ")
                .contains("{\"name\": \"extra.json\", \"class\": \"download\", \"raw\": 2, \"gzip\": ")
                .contains("\"brotli\": null")
                .contains("\"passed\": true");

        // One byte over the raw limit fails.
        Files.writeString(build.resolve("game.wasm"), "x".repeat(10_001));
        assertThat(DownloadSize.run(DownloadSize.Options.parse(
                        List.of("--budget", budget.toString(), "--dir", build.toString(), "--no-brotli"))))
                .isEqualTo(1);
    }

    @Test
    void anUnclassifiedFileFailsTheCheck() throws Exception {
        Path build = Files.createDirectories(dir.resolve("build"));
        Files.writeString(build.resolve("game.wasm"), "x");
        Files.writeString(build.resolve("new-thing.bin"), "x");
        Path budget = dir.resolve("budget.properties");
        Files.write(budget, List.of("file.game.wasm=download"));
        assertThatThrownBy(() -> DownloadSize.run(DownloadSize.Options.parse(
                        List.of("--budget", budget.toString(), "--dir", build.toString(), "--no-brotli"))))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Not classified")
                .hasMessageContaining("new-thing.bin");
    }

    @Test
    void tableShowsTheGzipBudgetAndHeadroom() {
        DownloadBudget budget = DownloadBudget.parse(
                List.of("file.a.wasm=download", "file.b.txt=report", "max.gzip.a.wasm=2000000"), "t");
        String table = DownloadSize.table(
                List.of(
                        new DownloadBudget.Measured("a.wasm", DownloadBudget.Kind.DOWNLOAD, 5_000_000, 1_500_000, null),
                        new DownloadBudget.Measured("b.txt", DownloadBudget.Kind.REPORT, 10, 30, 5L)),
                budget);
        assertThat(table)
                .contains("a.wasm")
                .contains("5.00 MB")
                .contains("2.00 MB")
                .contains("500.0 kB (25%)")
                .contains("n/a")
                .contains("total downloaded");
        assertThat(DownloadSize.human(999)).isEqualTo("999 B");
        assertThat(DownloadSize.human(1_500)).isEqualTo("1.5 kB");
    }
}
