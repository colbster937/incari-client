package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class TFutureTaskTest {

    @Test
    void getReturnsTheResultComputedOnAnotherThread() throws Exception {
        TFutureTask<String> task = new TFutureTask<>(() -> "done");
        Thread t = new Thread(task);
        t.start();

        assertThat(task.get()).isEqualTo("done");
        assertThat(task.isDone()).isTrue();
        assertThat(task.state()).isEqualTo(TFuture.State.SUCCESS);
        assertThat(task.resultNow()).isEqualTo("done");
        t.join();
    }

    @Test
    void runnableTaskReturnsTheGivenResult() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        TFutureTask<Integer> task = new TFutureTask<>(runs::incrementAndGet, 42);
        task.run();
        task.run(); // a completed task doesn't run again

        assertThat(task.get()).isEqualTo(42);
        assertThat(runs).hasValue(1);
    }

    @Test
    void failureIsReportedAsExecutionException() {
        IllegalStateException boom = new IllegalStateException("boom");
        TFutureTask<String> task = new TFutureTask<>(() -> {
            throw boom;
        });
        task.run();

        assertThatThrownBy(task::get).isInstanceOf(ExecutionException.class).hasCause(boom);
        assertThat(task.state()).isEqualTo(TFuture.State.FAILED);
        assertThat(task.exceptionNow()).isSameAs(boom);
        assertThatThrownBy(task::resultNow).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelBeforeRunPreventsRunningAndCallsDone() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicInteger doneCalls = new AtomicInteger();
        TFutureTask<String> task =
                new TFutureTask<>(() -> {
                    ran.set(true);
                    return "x";
                }) {
                    @Override
                    protected void done() {
                        doneCalls.incrementAndGet();
                    }
                };

        assertThat(task.cancel(false)).isTrue();
        assertThat(task.cancel(false)).isFalse();
        task.run();

        assertThat(ran).isFalse();
        assertThat(task.isCancelled()).isTrue();
        assertThat(task.state()).isEqualTo(TFuture.State.CANCELLED);
        assertThat(doneCalls).hasValue(1);
        assertThatThrownBy(task::get).isInstanceOf(CancellationException.class);
    }

    @Test
    void cancelWithInterruptStopsARunningTask() throws Exception {
        TCountDownLatch started = new TCountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        TFutureTask<String> task = new TFutureTask<>(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return "late";
        });
        Thread t = new Thread(task);
        t.start();
        started.await();

        assertThat(task.cancel(true)).isTrue();
        t.join();

        assertThat(interrupted).isTrue();
        assertThatThrownBy(task::get).isInstanceOf(CancellationException.class);
    }

    @Test
    void timedGetTimesOut() {
        TFutureTask<String> task = new TFutureTask<>(() -> "never run");

        assertThatThrownBy(() -> task.get(20, TimeUnit.MILLISECONDS)).isInstanceOf(TTimeoutException.class);
        assertThat(task.state()).isEqualTo(TFuture.State.RUNNING);
    }

    @Test
    void runAndResetLeavesTheTaskReusable() {
        AtomicInteger runs = new AtomicInteger();
        PeriodicTask periodic = new PeriodicTask(runs);

        assertThat(periodic.resetRun()).isTrue();
        assertThat(periodic.resetRun()).isTrue();
        assertThat(periodic.isDone()).isFalse();
        assertThat(runs).hasValue(2);
    }

    private static final class PeriodicTask extends TFutureTask<Integer> {
        PeriodicTask(AtomicInteger runs) {
            super(runs::incrementAndGet);
        }

        boolean resetRun() {
            return runAndReset();
        }
    }
}
