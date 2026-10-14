package incari.client.profiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FrameHistoryTest {

    @Test
    void keepsTheNewestFrames() {
        FrameHistory history = new FrameHistory(3);
        for (int frame = 1; frame <= 5; frame++) {
            history.add(frame);
        }

        assertThat(history.size()).isEqualTo(3);
        assertThat(history.ago(0)).isEqualTo(5);
        assertThat(history.ago(2)).isEqualTo(3);
    }

    @Test
    void framesPerSecondCoverTheWindow() {
        FrameHistory history = new FrameHistory(100);
        for (int frame = 0; frame < 100; frame++) {
            history.add(20);
        }

        // 50 frames of 20 ms fill a second.
        assertThat(history.framesPerSecond(1000)).isCloseTo(50, within(1e-9));
    }

    @Test
    void theOnePercentLowAveragesTheSlowestFrames() {
        FrameHistory history = new FrameHistory(200);
        for (int frame = 0; frame < 198; frame++) {
            history.add(10);
        }
        history.add(40);
        history.add(50);

        assertThat(history.slowestMean(1)).isEqualTo(45);
        assertThat(new FrameHistory(4).slowestMean(1)).isNaN();
    }

    @Test
    void tenthsWithoutFormatting() {
        assertThat(ProfilerOverlay.tenths(16.66)).isEqualTo("16.7");
        assertThat(ProfilerOverlay.tenths(0.04)).isEqualTo("0.0");
        assertThat(ProfilerOverlay.tenths(-1.26)).isEqualTo("-1.3");
        assertThat(ProfilerOverlay.tenths(Double.NaN)).isEqualTo("n/a");
    }

    @Test
    void zonesCountTowardsTheirPartOfTheFrame() {
        assertThat(FrameProfiler.outerZone("tick")).isEqualTo(FrameProfiler.outerZone("scheduledPacketProcessing"));
        assertThat(FrameProfiler.outerZone("frame")).isNotEqualTo(FrameProfiler.outerZone("tick"));
        assertThat(FrameProfiler.outerZone("sound")).isZero();
        assertThat(FrameProfiler.innerZone("render")).isEqualTo(FrameProfiler.innerZone("submit"));
        assertThat(FrameProfiler.innerZone("extract")).isNotEqualTo(FrameProfiler.innerZone("render"));
        assertThat(FrameProfiler.innerZone("frameLimiter")).isZero();
    }
}
