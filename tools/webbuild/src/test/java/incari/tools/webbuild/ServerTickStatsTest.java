package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerTickStatsTest {

    @Test
    void pausedTicksAreLeftOutOfTheRest() {
        ServerTickStats stats = new ServerTickStats();
        // Two stretches of 1 s: 19 ticks of 10 ms and one of 80 ms, then 10 running ticks of 20 ms and
        // 5 paused ones.
        stats.add(ticks(19, 10, 0, 400), 1);
        stats.add(ticks(1, 80, 0, 410), 0);
        stats.add(ticks(10, 20, 0, 420), 1);
        stats.add(ticks(5, 1, 1, 430), 0);
        Map<String, Double> figures = stats.figures();
        assertThat(stats.ticks()).isEqualTo(35);
        assertThat(figures.get("serverTicksPerSecond")).isCloseTo(15, within(1e-9));
        assertThat(figures.get("serverTickMillis")).isCloseTo((19 * 10 + 80 + 10 * 20) / 30.0, within(1e-9));
        // Nearest rank: the 29th of 30 sorted ticks.
        assertThat(figures.get("serverTickP95Millis")).isEqualTo(20);
        assertThat(figures.get("serverTickMaxMillis")).isEqualTo(80);
        assertThat(figures.get("serverPausedTicks")).isEqualTo(5);
        assertThat(figures.get("serverChunks")).isEqualTo(430);
    }

    @Test
    void nothingMeasuredIsNotANumber() {
        Map<String, Double> figures = new ServerTickStats().figures();
        assertThat(figures.get("serverTicksPerSecond")).isNaN();
        assertThat(figures.get("serverTickMillis")).isNaN();
        assertThat(figures.get("serverChunks")).isNaN();
    }

    @Test
    void aTableIsWholeRows() {
        assertThatThrownBy(() -> new ServerTickStats().add(new double[ServerTickStats.COLUMNS + 1], 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double[] ticks(int count, double millis, double paused, double chunks) {
        double[] table = new double[count * ServerTickStats.COLUMNS];
        for (int tick = 0; tick < count; tick++) {
            int row = tick * ServerTickStats.COLUMNS;
            table[row] = tick * 50;
            table[row + ServerTickStats.MILLIS] = millis;
            table[row + ServerTickStats.PAUSED] = paused;
            table[row + ServerTickStats.CHUNKS] = chunks;
        }
        return table;
    }
}
