package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class TScheduledThreadPoolExecutorTest {

    @Test
    void delayedTasksRunInOrderOfDueTime() throws Exception {
        TScheduledThreadPoolExecutor pool = new TScheduledThreadPoolExecutor(1);
        List<Integer> order = new CopyOnWriteArrayList<>();
        TCountDownLatch done = new TCountDownLatch(3);
        pool.schedule(
                () -> {
                    order.add(3);
                    done.countDown();
                },
                120,
                TimeUnit.MILLISECONDS);
        pool.schedule(
                () -> {
                    order.add(1);
                    done.countDown();
                },
                20,
                TimeUnit.MILLISECONDS);
        pool.schedule(
                () -> {
                    order.add(2);
                    done.countDown();
                },
                60,
                TimeUnit.MILLISECONDS);

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1, 2, 3);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void scheduledCallableReturnsItsResultNoEarlierThanTheDelay() throws Exception {
        TScheduledExecutorService pool = TExecutors.newScheduledThreadPool(2);
        long start = System.nanoTime();
        TScheduledFuture<String> future = pool.schedule(() -> "hello", 50, TimeUnit.MILLISECONDS);

        assertThat(future.getDelay(TimeUnit.MILLISECONDS)).isPositive();
        assertThat(future.get()).isEqualTo("hello");
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(50));
        pool.shutdown();
    }

    @Test
    void fixedRateTaskRepeatsUntilCancelled() throws Exception {
        TScheduledExecutorService pool = TExecutors.newSingleThreadScheduledExecutor();
        AtomicInteger runs = new AtomicInteger();
        TCountDownLatch threeRuns = new TCountDownLatch(3);
        TScheduledFuture<?> future = pool.scheduleAtFixedRate(
                () -> {
                    runs.incrementAndGet();
                    threeRuns.countDown();
                },
                0,
                10,
                TimeUnit.MILLISECONDS);

        assertThat(threeRuns.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isFalse();
        assertThat(future.cancel(false)).isTrue();
        int after = runs.get();
        Thread.sleep(60);
        assertThat(runs.get()).isLessThanOrEqualTo(after + 1);
        assertThatThrownBy(future::get).isInstanceOf(CancellationException.class);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void fixedDelayTaskStopsAfterItThrows() throws Exception {
        TScheduledThreadPoolExecutor pool = new TScheduledThreadPoolExecutor(1);
        AtomicInteger runs = new AtomicInteger();
        TScheduledFuture<?> future = pool.scheduleWithFixedDelay(
                () -> {
                    if (runs.incrementAndGet() == 2) {
                        throw new IllegalStateException("second run fails");
                    }
                },
                0,
                5,
                TimeUnit.MILLISECONDS);

        assertThatThrownBy(future::get).hasCauseInstanceOf(IllegalStateException.class);
        Thread.sleep(30);
        assertThat(runs).hasValue(2);
        pool.shutdown();
    }

    @Test
    void shutdownRunsDelayedTasksButDropsPeriodicOnesByDefault() throws Exception {
        TScheduledThreadPoolExecutor pool = new TScheduledThreadPoolExecutor(1);
        AtomicInteger delayedRuns = new AtomicInteger();
        pool.schedule(delayedRuns::incrementAndGet, 30, TimeUnit.MILLISECONDS);
        TScheduledFuture<?> periodic = pool.scheduleAtFixedRate(() -> {}, 1, 1, TimeUnit.HOURS);

        pool.shutdown();

        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delayedRuns).hasValue(1);
        assertThat(periodic.isCancelled()).isTrue();
    }

    @Test
    void shutdownNowReturnsPendingTasks() throws Exception {
        TScheduledThreadPoolExecutor pool = new TScheduledThreadPoolExecutor(1);
        pool.schedule(() -> {}, 1, TimeUnit.HOURS);
        pool.schedule(() -> {}, 2, TimeUnit.HOURS);

        assertThat(pool.shutdownNow()).hasSize(2);
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void removeOnCancelTakesTasksOutOfTheQueue() {
        TScheduledThreadPoolExecutor pool = new TScheduledThreadPoolExecutor(1);
        pool.setRemoveOnCancelPolicy(true);
        TScheduledFuture<?> future = pool.schedule(() -> {}, 1, TimeUnit.HOURS);
        assertThat(pool.getQueue()).hasSize(1);

        future.cancel(false);

        assertThat(pool.getQueue()).isEmpty();
        pool.shutdownNow();
    }

    @Test
    void executeAndSubmitRunImmediately() throws Exception {
        TScheduledThreadPoolExecutor pool = new TScheduledThreadPoolExecutor(1);
        TCountDownLatch ran = new TCountDownLatch(1);
        pool.execute(ran::countDown);

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.submit(() -> 5).get()).isEqualTo(5);
        pool.shutdown();
    }
}
