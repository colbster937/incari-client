package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import incari.tools.webbuild.DownloadBudget.Kind;
import incari.tools.webbuild.DownloadBudget.Measure;
import incari.tools.webbuild.DownloadBudget.Measured;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DownloadBudgetTest {

    private static final DownloadBudget BUDGET = DownloadBudget.parse(
            List.of(
                    "# a comment",
                    "",
                    "file.game.wasm=download",
                    "file.page.html = download",
                    "file.extra.json=download",
                    "file.game.teadbg=debug",
                    "file.*.txt=report",
                    "file.client.jar=vendor",
                    "max.gzip.total=1_000",
                    "max.gzip.game.wasm=900",
                    "max.raw.game.wasm=5000",
                    "max.gzip.extra.json=50",
                    "max.brotli.page.html=10"),
            "test");

    @Test
    void classifiesByNameThenGlob() {
        assertThat(BUDGET.kindOf("game.wasm")).isEqualTo(Kind.DOWNLOAD);
        assertThat(BUDGET.kindOf("page.html")).isEqualTo(Kind.DOWNLOAD);
        assertThat(BUDGET.kindOf("removed.txt")).isEqualTo(Kind.REPORT);
        assertThat(BUDGET.kindOf("sub/removed.txt")).isNull();
        assertThat(BUDGET.kindOf("shaders.json")).isNull();
        assertThat(BUDGET.limit(Measure.GZIP, "total")).isNotNull();
        assertThat(BUDGET.limit(Measure.BROTLI, "game.wasm")).isNull();
    }

    @Test
    void passesUnderBudgetAndSkipsAnOptionalFileNotBuiltYet() {
        DownloadBudget.Verdict verdict = BUDGET.judge(
                List.of(
                        new Measured("game.wasm", Kind.DOWNLOAD, 4000, 800, 500L),
                        new Measured("page.html", Kind.DOWNLOAD, 100, 60, 9L),
                        // Debug and vendor files never count toward the total.
                        new Measured("game.teadbg", Kind.DEBUG, 9000, 9000, 9000L),
                        new Measured("client.jar", Kind.VENDOR, 9000, 9000, 9000L)),
                Set.of("extra.json", "client.jar"));
        assertThat(verdict.failures()).isEmpty();
        assertThat(verdict.notes()).singleElement().asString().contains("extra.json isn't built yet");
        assertThat(verdict.outcomes())
                .filteredOn(o -> o.limit().file().equals("total"))
                .singleElement()
                .extracting(DownloadBudget.Outcome::actual)
                .isEqualTo(860L);
    }

    @Test
    void failsOverBudgetAndOnAMissingBudgetedFile() {
        DownloadBudget.Verdict verdict =
                BUDGET.judge(List.of(new Measured("game.wasm", Kind.DOWNLOAD, 6000, 950, 500L)), Set.of("extra.json"));
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures())
                .containsExactly(
                        "max.gzip.game.wasm: 950 bytes, the budget is 900",
                        "max.raw.game.wasm: 6000 bytes, the budget is 5000",
                        "page.html has a limit but isn't in the build output");
    }

    @Test
    void brotliLimitsAreSkippedWithoutBrotli() {
        DownloadBudget.Verdict verdict = BUDGET.judge(
                List.of(
                        new Measured("game.wasm", Kind.DOWNLOAD, 10, 10, null),
                        new Measured("page.html", Kind.DOWNLOAD, 10, 10, null)),
                Set.of("extra.json"));
        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.notes()).anyMatch(n -> n.contains("max.brotli.page.html wasn't checked"));
        assertThat(DownloadBudget.total(
                        List.of(new Measured("game.wasm", Kind.DOWNLOAD, 10, 10, null)), Measure.BROTLI))
                .isEqualTo(-1);
    }

    @Test
    void rejectsMistakes() {
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("file.a=shipped"), "t"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("unknown class");
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("file.a=download", "file.a=debug"), "t"))
                .hasMessageContaining("classified twice");
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("max.zstd.a=1", "file.a=download"), "t"))
                .hasMessageContaining("unknown measure");
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("file.a=download", "max.gzip.a=lots"), "t"))
                .hasMessageContaining("byte count");
        // Only what players download is budgeted.
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("file.a=debug", "max.gzip.a=1"), "t"))
                .hasMessageContaining("isn't classified 'download'");
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("max.gzip.b=1"), "t"))
                .hasMessageContaining("isn't classified 'download'");
        assertThatThrownBy(() -> DownloadBudget.parse(List.of("budget=1"), "t")).hasMessageContaining("unknown key");
        DownloadBudget conflicting = DownloadBudget.parse(List.of("file.*.txt=report", "file.a.*=download"), "t");
        assertThatThrownBy(() -> conflicting.kindOf("a.txt")).hasMessageContaining("classify it differently");
    }

    /** The checked-in budget parses, and classifies everything a development build writes today. */
    @Test
    void theRepositoryBudgetIsValid() throws Exception {
        Path file = Path.of("../../app-web/download-budget.properties");
        assertThat(Files.isRegularFile(file))
                .as(file.toAbsolutePath().toString())
                .isTrue();
        DownloadBudget budget = DownloadBudget.load(file);
        for (String name : List.of(
                "incari.wasm",
                "incari.wasm-runtime.js",
                "index.html",
                "shaders.json",
                "incari.wasm.teadbg",
                "incari.wasm-deobfuscator.wasm",
                "problems.txt",
                "async.txt",
                "removed.txt",
                "signatures.txt",
                "client.jar")) {
            assertThat(budget.kindOf(name)).as(name).isNotNull();
        }
        assertThat(budget.kindOf("incari.wasm")).isEqualTo(Kind.DOWNLOAD);
        assertThat(budget.kindOf("client.jar")).isEqualTo(Kind.VENDOR);
        assertThat(budget.limit(Measure.GZIP, DownloadBudget.TOTAL)).isNotNull();
        assertThat(budget.limit(Measure.GZIP, "incari.wasm")).isNotNull();
    }
}
