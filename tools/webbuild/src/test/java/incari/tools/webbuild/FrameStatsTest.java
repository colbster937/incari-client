package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrameStatsTest {

    @Test
    void frameRatesComeFromTheTimeBetweenFrames() {
        FrameStats stats = new FrameStats();
        // 99 frames of 16 ms and one of 100 ms: the median is 62.5 fps, the 1% low (the slowest
        // frame, 1% of 100) 10 fps.
        double[] table = new double[100 * FrameStats.COLUMNS];
        for (int frame = 0; frame < 100; frame++) {
            int row = frame * FrameStats.COLUMNS;
            java.util.Arrays.fill(table, row, row + FrameStats.COLUMNS, Double.NaN);
            table[row + FrameStats.INTERVAL] = frame == 50 ? 100 : 16;
            table[row + FrameStats.TICK] = 2;
            table[row + FrameStats.DRAWS] = frame < 50 ? 100 : 300;
            table[row + FrameStats.TERRAIN_DRAWS] = -1;
            table[row + FrameStats.HEAP] = frame;
        }
        stats.add(table);

        Map<String, Double> figures = stats.figures();

        assertThat(figures.get("frames")).isEqualTo(100);
        assertThat(figures.get("fpsMedian")).isCloseTo(62.5, within(1e-9));
        assertThat(figures.get("fpsLow1")).isCloseTo(10, within(1e-9));
        assertThat(figures.get("frameP99Millis")).isEqualTo(16);
        assertThat(figures.get("tickMillis")).isEqualTo(2);
        assertThat(figures.get("drawCalls")).isEqualTo(200);
        assertThat(figures.get("terrainDrawCalls")).isNaN();
        assertThat(figures.get("gpuMillis")).isNaN();
        assertThat(figures.get("heapMaxMB")).isEqualTo(99);
        assertThat(figures.get("gcCount")).isNaN();
    }

    @Test
    void theOnePercentLowAveragesTheSlowestOnePercent() {
        double[] sorted = new double[200];
        java.util.Arrays.fill(sorted, 10);
        sorted[198] = 40;
        sorted[199] = 50;

        assertThat(FrameStats.slowestMean(sorted, 1)).isEqualTo(45);
        assertThat(FrameStats.slowestMean(new double[] {5}, 1)).isEqualTo(5);
        assertThat(FrameStats.percentile(sorted, 99)).isEqualTo(10);
        assertThat(FrameStats.percentile(sorted, 50)).isEqualTo(10);
        assertThat(FrameStats.percentile(new double[0], 50)).isNaN();
    }

    @Test
    void gcPausesAreTheMainThreadsMinorAndMajorCollections() {
        List<JsonObject> events = List.of(
                json(
                        "{\"name\":\"thread_name\",\"ph\":\"M\",\"pid\":1,\"tid\":7,\"args\":{\"name\":\"CrRendererMain\"}}"),
                json("{\"name\":\"MinorGC\",\"ph\":\"X\",\"pid\":1,\"tid\":7,\"dur\":1500}"),
                json("{\"name\":\"MajorGC\",\"ph\":\"X\",\"pid\":1,\"tid\":7,\"dur\":12000}"),
                // A worker's collection doesn't pause the game.
                json("{\"name\":\"MajorGC\",\"ph\":\"X\",\"pid\":1,\"tid\":9,\"dur\":90000}"),
                json("{\"name\":\"V8.GCScavenger\",\"ph\":\"X\",\"pid\":1,\"tid\":7,\"dur\":1400}"));

        GcStats gc = GcStats.of(events);

        assertThat(gc.count()).isEqualTo(2);
        assertThat(gc.totalMillis()).isCloseTo(13.5, within(1e-9));
        assertThat(gc.maxMillis()).isCloseTo(12, within(1e-9));
        assertThat(GcStats.NONE.plus(gc)).isEqualTo(gc);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
