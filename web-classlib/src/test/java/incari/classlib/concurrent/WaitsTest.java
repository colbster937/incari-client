package incari.classlib.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WaitsTest {

    private final PrintStream originalErr = System.err;

    @AfterEach
    void restore() {
        System.setErr(originalErr);
        Waits.watch(new Thread(() -> {}));
    }

    private String errors(Runnable action) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        action.run();
        System.setErr(originalErr);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aTimedWaitStillWaitsAndReturnsOnAnyThread() throws InterruptedException {
        Object monitor = new Object();
        Waits.watch(Thread.currentThread());
        long start = System.nanoTime();
        synchronized (monitor) {
            Waits.wait(monitor, 20);
        }
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(15_000_000L);
    }

    @Test
    void aLongWaitOnTheRenderThreadSaysWhereItWas() {
        assertThat(errors(() -> Waits.waited(80))).contains("[Incari] The render thread waited 80 ms");
        assertThat(errors(() -> Waits.waited(3))).isEmpty();
    }
}
