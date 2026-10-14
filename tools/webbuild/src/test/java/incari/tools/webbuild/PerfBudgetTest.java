package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PerfBudgetTest {

    private static final PerfBudget BUDGET = PerfBudget.parse(
            List.of(
                    "# heap",
                    "max.*.heapLiveMB=600",
                    "max.load.titleHeapLiveMB=400",
                    "max.*.gcMaxPauseMillis=16",
                    "desktop.max.load.warmTitleMillis=3_000",
                    "desktop.max.*.gcMaxPauseMillis=8"),
            "budgets.properties");

    @Test
    void checksEveryFigureAPatternCovers() {
        Map<String, Double> figures = new LinkedHashMap<>();
        figures.put("flythrough.heapLiveMB", 650.0);
        figures.put("combat.heapLiveMB", 500.0);
        figures.put("flythrough.fpsMedian", 60.0);
        figures.put("load.titleHeapLiveMB", 390.0);

        List<PerfBudget.Check> checks = BUDGET.check("chromebook", figures);

        assertThat(checks)
                .extracting(PerfBudget.Check::figure)
                .containsExactly("flythrough.heapLiveMB", "combat.heapLiveMB", "load.titleHeapLiveMB");
        assertThat(checks)
                .filteredOn(PerfBudget.Check::over)
                .extracting(PerfBudget.Check::figure)
                .containsExactly("flythrough.heapLiveMB");
    }

    @Test
    void aMachinesOwnLimitWins() {
        Map<String, Double> figures = Map.of("lobby.gcMaxPauseMillis", 10.0, "load.warmTitleMillis", 2500.0);

        assertThat(BUDGET.check("desktop", figures))
                .filteredOn(PerfBudget.Check::over)
                .extracting(PerfBudget.Check::figure)
                .containsExactly("lobby.gcMaxPauseMillis");
        // Another machine has no time limit, and the general pause limit.
        assertThat(BUDGET.check("laptop", figures))
                .filteredOn(PerfBudget.Check::over)
                .isEmpty();
    }

    @Test
    void aStarIsOnePartOfTheKey() {
        assertThat(BUDGET.check("x", Map.of("a.b.heapLiveMB", 999.0))).isEmpty();
    }

    @Test
    void figuresNotMeasuredArentChecked() {
        assertThat(BUDGET.check("x", Map.of("flythrough.heapLiveMB", Double.NaN)))
                .isEmpty();
    }

    @Test
    void rejectsALineThatIsntABudget() {
        assertThatThrownBy(() -> PerfBudget.parse(List.of("heap=5"), "b")).isInstanceOf(CliException.class);
        assertThatThrownBy(() -> PerfBudget.parse(List.of("max.heap=lots"), "b"))
                .isInstanceOf(CliException.class);
    }
}
