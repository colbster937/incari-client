package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class TForkJoinPoolTest {

    /** Sums a range by splitting it until pieces are small. */
    private static final class Sum extends TRecursiveTask<Long> {
        private final long from;
        private final long to;

        Sum(long from, long to) {
            this.from = from;
            this.to = to;
        }

        @Override
        protected Long compute() {
            if (to - from <= 1_000) {
                long sum = 0;
                for (long i = from; i < to; i++) {
                    sum += i;
                }
                return sum;
            }
            long mid = (from + to) >>> 1;
            Sum left = new Sum(from, mid);
            Sum right = new Sum(mid, to);
            left.fork();
            return right.compute() + left.join();
        }
    }

    /** Increments each slot of an array, splitting with invokeAll. */
    private static final class Increment extends TRecursiveAction {
        private final int[] array;
        private final int from;
        private final int to;

        Increment(int[] array, int from, int to) {
            this.array = array;
            this.from = from;
            this.to = to;
        }

        @Override
        protected void compute() {
            if (to - from <= 100) {
                for (int i = from; i < to; i++) {
                    array[i]++;
                }
                return;
            }
            int mid = (from + to) >>> 1;
            invokeAll(new Increment(array, from, mid), new Increment(array, mid, to));
        }
    }

    @Test
    void divideAndConquerSum() {
        TForkJoinPool pool = new TForkJoinPool(4);
        long n = 1_000_000;

        assertThat(pool.invoke(new Sum(0, n))).isEqualTo(n * (n - 1) / 2);
        pool.shutdown();
    }

    @Test
    void singleWorkerPoolCannotDeadlockOnJoins() {
        TForkJoinPool pool = new TForkJoinPool(1);

        assertThat(pool.invoke(new Sum(0, 100_000))).isEqualTo(100_000L * 99_999 / 2);
        pool.shutdown();
    }

    @Test
    void recursiveActionWithInvokeAll() {
        int[] array = new int[10_000];
        TForkJoinPool.commonPool().invoke(new Increment(array, 0, array.length));

        for (int value : array) {
            assertThat(value).isEqualTo(1);
        }
    }

    @Test
    void forkOutsideAPoolUsesTheCommonPool() {
        Sum task = new Sum(0, 10_000);
        task.fork();

        assertThat(task.join()).isEqualTo(10_000L * 9_999 / 2);
        assertThat(TForkJoinTask.inForkJoinPool()).isFalse();
        assertThat(TForkJoinTask.getPool()).isNull();
    }

    @Test
    void joinRethrowsTheTaskFailure() {
        TRecursiveTask<Integer> failing = new TRecursiveTask<>() {
            @Override
            protected Integer compute() {
                throw new IllegalArgumentException("bad input");
            }
        };

        assertThatThrownBy(failing::invoke)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad input");
        assertThat(failing.isCompletedAbnormally()).isTrue();
        assertThat(failing.getException()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submittedCallableAndGet() throws Exception {
        TForkJoinPool pool = new TForkJoinPool(2);
        TForkJoinTask<String> task = pool.submit(() -> Thread.currentThread().getName());

        assertThat(task.get()).isNotEmpty();
        TForkJoinTask<?> failing = pool.submit((Callable<String>) () -> {
            throw new Exception("checked");
        });
        assertThatThrownBy(failing::get).isInstanceOf(ExecutionException.class);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void invokeAllRunsEveryCallable() throws Exception {
        TForkJoinPool pool = new TForkJoinPool(3);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int value = i;
            tasks.add(() -> value * value);
        }

        List<TFuture<Integer>> futures = pool.invokeAll(tasks);

        int sum = 0;
        for (TFuture<Integer> f : futures) {
            sum += f.get();
        }
        assertThat(sum).isEqualTo(2470);
        pool.shutdown();
    }

    @Test
    void customWorkerThreadsLikeMinecraftsGetTheirHooksCalled() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        AtomicInteger names = new AtomicInteger();
        TForkJoinPool pool = new TForkJoinPool(
                2,
                p -> {
                    TForkJoinWorkerThread worker = new TForkJoinWorkerThread(p) {
                        @Override
                        protected void onStart() {
                            events.add("start");
                            super.onStart();
                        }

                        @Override
                        protected void onTermination(Throwable exception) {
                            events.add(exception == null ? "end" : "died: " + exception.getMessage());
                            super.onTermination(exception);
                        }
                    };
                    worker.setName("Worker-Main-" + names.incrementAndGet());
                    return worker;
                },
                (thread, e) -> uncaught.set(e),
                true);
        TCountDownLatch ran = new TCountDownLatch(1);
        pool.execute(() -> {
            assertThat(Thread.currentThread().getName()).startsWith("Worker-Main-");
            assertThat(TForkJoinTask.inForkJoinPool()).isTrue();
            ran.countDown();
        });
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

        pool.execute(() -> {
            throw new IllegalStateException("task failed");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (uncaught.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(uncaught.get()).hasMessage("task failed");
        // The failing task ended its worker; this one runs on a fresh worker that ends normally.
        assertThat(pool.submit(() -> "still works").get(5, TimeUnit.SECONDS)).isEqualTo("still works");

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).contains("start", "died: task failed", "end");
        assertThat(pool.getAsyncMode()).isTrue();
    }

    @Test
    void terminationWaitsForWorkerHooks() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        TForkJoinPool pool = new TForkJoinPool(
                1,
                p -> new TForkJoinWorkerThread(p) {
                    @Override
                    protected void onTermination(Throwable exception) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ended.incrementAndGet();
                    }
                },
                null,
                false);
        assertThat(pool.submit(() -> "ran").get(5, TimeUnit.SECONDS)).isEqualTo("ran");

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ended.get()).isEqualTo(1);
    }

    @Test
    void managedBlockLetsAnotherWorkerRun() throws Exception {
        TForkJoinPool pool = new TForkJoinPool(1);
        TCountDownLatch second = new TCountDownLatch(1);
        TForkJoinTask<?> blocked = pool.submit(() -> {
            try {
                TForkJoinPool.managedBlock(new TForkJoinPool.ManagedBlocker() {
                    @Override
                    public boolean block() throws InterruptedException {
                        second.await();
                        return true;
                    }

                    @Override
                    public boolean isReleasable() {
                        return second.getCount() == 0;
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // With parallelism 1 this only runs because managedBlock compensates.
        pool.execute(second::countDown);

        blocked.get(5, TimeUnit.SECONDS);
        assertThat(blocked.isDone()).isTrue();
        pool.shutdown();
    }

    @Test
    void shutdownNowCancelsQueuedTasks() throws Exception {
        TForkJoinPool pool = new TForkJoinPool(1);
        TCountDownLatch started = new TCountDownLatch(1);
        TCountDownLatch gate = new TCountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        started.await();
        TForkJoinTask<?> queued = pool.submit(() -> {});

        pool.shutdownNow();

        assertThat(queued.isCancelled()).isTrue();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(TRejectedExecutionException.class);
    }

    @Test
    void commonPoolIgnoresShutdown() {
        TForkJoinPool common = TForkJoinPool.commonPool();
        common.shutdown();

        assertThat(common.isShutdown()).isFalse();
        assertThat(TForkJoinPool.getCommonPoolParallelism()).isPositive();
        assertThat(common.awaitQuiescence(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void completeAndCancel() throws Exception {
        TRecursiveTask<String> task = new TRecursiveTask<>() {
            @Override
            protected String compute() {
                return "computed";
            }
        };
        task.complete("given");
        assertThat(task.join()).isEqualTo("given");

        TRecursiveAction action = new TRecursiveAction() {
            @Override
            protected void compute() {}
        };
        assertThat(action.cancel(true)).isTrue();
        assertThat(action.isCancelled()).isTrue();
        assertThat(action.state()).isEqualTo(TFuture.State.CANCELLED);

        action.reinitialize();
        action.invoke();
        assertThat(action.isCompletedNormally()).isTrue();
    }
}
