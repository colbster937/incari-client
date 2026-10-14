package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PerfBaselineTest {

    private static final List<String> FILE = List.of(
            "# rules",
            "rule.fpsMedian=higher 5 1",
            "rule.drawCalls=lower 10 5",
            "",
            "desktop.flythrough.fpsMedian=60",
            "desktop.flythrough.drawCalls=400",
            "desktop.flythrough.frames=3600",
            "laptop.flythrough.fpsMedian=30");

    private final PerfBaseline baseline = PerfBaseline.parse(FILE, "baseline.properties");

    @Test
    void aFigureRegressesOnlyPastBothLimits() {
        List<PerfBaseline.Comparison> comparisons = baseline.compare(
                "desktop",
                Map.of("flythrough.fpsMedian", 56.0, "flythrough.drawCalls", 404.0, "flythrough.frames", 1.0));

        assertThat(comparisons)
                .extracting(PerfBaseline.Comparison::key, PerfBaseline.Comparison::regressed)
                .containsExactlyInAnyOrder(
                        // 4 fps worse: more than 5% of 60 and more than 1 fps.
                        org.assertj.core.groups.Tuple.tuple("flythrough.fpsMedian", true),
                        // 4 more draws: 1%, and under the 5 absolute.
                        org.assertj.core.groups.Tuple.tuple("flythrough.drawCalls", false));
    }

    @Test
    void betterIsNeverARegression() {
        assertThat(baseline.compare("desktop", Map.of("flythrough.fpsMedian", 144.0, "flythrough.drawCalls", 40.0)))
                .noneMatch(PerfBaseline.Comparison::regressed);
    }

    @Test
    void anotherMachinesFiguresAreItsOwn() {
        assertThat(baseline.figures("laptop")).containsExactly(Map.entry("flythrough.fpsMedian", 30.0));
        assertThat(baseline.compare("nobody", Map.of("flythrough.fpsMedian", 1.0)))
                .isEmpty();
    }

    @Test
    void updatingReplacesOnlyThatMachine() {
        List<String> updated = baseline.withFigures(
                "desktop", Map.of("flythrough.fpsMedian", 61.25, "flythrough.gpuMillis", Double.NaN));

        assertThat(updated)
                .contains(
                        "rule.fpsMedian=higher 5 1",
                        "laptop.flythrough.fpsMedian=30",
                        "desktop.flythrough.fpsMedian=61.25")
                .doesNotContain("desktop.flythrough.drawCalls=400")
                .noneMatch(line -> line.contains("gpuMillis"));
    }
}
