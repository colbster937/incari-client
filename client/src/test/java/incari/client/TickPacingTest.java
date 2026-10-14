package incari.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link TickPacing} runs every tick the game would, spread over frames, and keeps up when slow. */
class TickPacingTest {

    @BeforeEach
    void enable() {
        TickPacing.setEnabled(true);
    }

    @AfterEach
    void disable() {
        TickPacing.setEnabled(false);
    }

    @Test
    void offRunsWhatIsDue() {
        TickPacing.setEnabled(false);
        assertThat(TickPacing.ticksToRun(7, true)).isEqualTo(7);
    }

    @Test
    void spreadsABurstAtSixtyFps() {
        warmUp(60);
        // A 300 ms frame: six ticks due at once.
        int first = TickPacing.ticksToRun(6, true);
        assertThat(first).isLessThanOrEqualTo(2);
        int total = first;
        int frames = 1;
        while (TickPacing.debt() > 0) {
            int run = TickPacing.ticksToRun(0, true);
            assertThat(run).isBetween(1, 2);
            total += run;
            frames++;
        }
        assertThat(total).isEqualTo(6);
        assertThat(frames).isLessThanOrEqualTo(5);
    }

    @Test
    void runsEveryTickTheGameWouldWhileBurstsComeApart() {
        Random random = new Random(20261003);
        int due = 0;
        int run = 0;
        for (int frame = 0; frame < 100_000; frame++) {
            // Mostly 60 fps, with a slow frame now and then, never two within ten frames.
            int ticks = frame % 50 == 0 ? random.nextInt(11) : (random.nextInt(3) == 0 ? 1 : 0);
            due += ticks;
            run += TickPacing.ticksToRun(ticks, true);
        }
        assertThat(run + TickPacing.debt()).isEqualTo(due);
    }

    @Test
    void neverRunsMoreThanTheGameAfterALongStall() {
        warmUp(60);
        // A two-second stall: the game runs ten ticks and drops the rest.
        int total = TickPacing.ticksToRun(40, true);
        while (TickPacing.debt() > 0) {
            total += TickPacing.ticksToRun(0, true);
        }
        assertThat(total).isEqualTo(10);
    }

    @Test
    void keepsUpWhenTheFrameRateIsLow() {
        // About 8 fps: two or three ticks due every frame.
        int maxDebt = 0;
        for (int frame = 0; frame < 1000; frame++) {
            TickPacing.ticksToRun(frame % 2 == 0 ? 2 : 3, true);
            maxDebt = Math.max(maxDebt, TickPacing.debt());
        }
        assertThat(maxDebt).isLessThanOrEqualTo(6);
    }

    @Test
    void waitsWhileTimeStands() {
        warmUp(60);
        TickPacing.ticksToRun(4, true);
        int owed = TickPacing.debt();
        assertThat(TickPacing.ticksToRun(0, false)).isZero();
        assertThat(TickPacing.debt()).isEqualTo(owed);
    }

    private static void warmUp(int fps) {
        int ticksPerFrameTimes3 = 60 / fps;
        for (int frame = 0; frame < 300; frame++) {
            TickPacing.ticksToRun(frame % 3 < ticksPerFrameTimes3 ? 1 : 0, true);
        }
        while (TickPacing.debt() > 0) {
            TickPacing.ticksToRun(0, true);
        }
    }
}
