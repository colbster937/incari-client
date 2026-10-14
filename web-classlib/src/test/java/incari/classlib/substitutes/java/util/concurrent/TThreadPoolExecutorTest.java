package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class TThreadPoolExecutorTest {

    @Test
    void fixedPoolRunsEveryTaskAndTerminates() throws Exception {
        TExecutorService pool = TExecutors.newFixedThreadPool(3);
        AtomicInteger count = new AtomicInteger();
        List<TFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(count::incrementAndGet));
        }
        for (TFuture<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.isTerminated()).isTrue();
        assertThat(count).hasValue(100);
    }

    @Test
    void queuedTasksStillRunAfterShutdown() throws Exception {
        TExecutorService pool = TExecutors.newSingleThreadExecutor();
        TCountDownLatch gate = new TCountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        pool.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < 5; i++) {
            pool.execute(count::incrementAndGet);
        }
        pool.shutdown();
        assertThat(pool.isTerminated()).isFalse();
        gate.countDown();

        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(count).hasValue(5);
    }

    @Test
    void rejectsTasksAfterShutdown() {
        TExecutorService pool = TExecutors.newFixedThreadPool(1);
        pool.shutdown();

        assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(TRejectedExecutionException.class);
    }

    @Test
    void saturatedPoolUsesItsRejectionPolicy() throws Exception {
        TCountDownLatch gate = new TCountDownLatch(1);
        Runnable blocker = () -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        TThreadPoolExecutor pool = new TThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new TLinkedBlockingQueue<>(1));
        pool.execute(blocker);
        pool.execute(blocker);

        assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(TRejectedExecutionException.class);

        pool.setRejectedExecutionHandler(new TThreadPoolExecutor.CallerRunsPolicy());
        Thread[] ranOn = new Thread[1];
        pool.execute(() -> ranOn[0] = Thread.currentThread());
        assertThat(ranOn[0]).isSameAs(Thread.currentThread());

        pool.setRejectedExecutionHandler(new TThreadPoolExecutor.DiscardPolicy());
        pool.execute(() -> {});

        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.getCompletedTaskCount()).isEqualTo(2);
    }

    @Test
    void shutdownNowReturnsQueuedTasksAndInterruptsWorkers() throws Exception {
        TCountDownLatch started = new TCountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        TThreadPoolExecutor pool = new TThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new TLinkedBlockingQueue<>());
        pool.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });
        Runnable queued = () -> {};
        pool.execute(queued);
        started.await();

        List<Runnable> pending = pool.shutdownNow();

        assertThat(pending).containsExactly(queued);
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).hasValue(1);
    }

    @Test
    void hooksRunAroundTasksAndAtTermination() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        TThreadPoolExecutor pool = new TThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new TLinkedBlockingQueue<>()) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                events.add("before");
            }

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                events.add(t == null ? "after" : "after " + t.getMessage());
            }

            @Override
            protected void terminated() {
                events.add("terminated");
            }
        };
        pool.execute(() -> events.add("task"));
        pool.execute(() -> {
            throw new IllegalStateException("boom");
        });
        pool.execute(() -> events.add("task after the failure"));
        pool.shutdown();

        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events)
                .containsExactly(
                        "before",
                        "task",
                        "after",
                        "before",
                        "after boom",
                        "before",
                        "task after the failure",
                        "after",
                        "terminated");
    }

    @Test
    void cachedPoolGrowsAndIdleThreadsTimeOut() throws Exception {
        TThreadPoolExecutor pool =
                new TThreadPoolExecutor(0, Integer.MAX_VALUE, 50, TimeUnit.MILLISECONDS, new TSynchronousQueue<>());
        TCountDownLatch running = new TCountDownLatch(3);
        TCountDownLatch gate = new TCountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            pool.execute(() -> {
                running.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.getPoolSize()).isEqualTo(3);
        gate.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getPoolSize() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(pool.getPoolSize()).isZero();
        assertThat(pool.getLargestPoolSize()).isEqualTo(3);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void invokeAllAndInvokeAny() throws Exception {
        TExecutorService pool = TExecutors.newFixedThreadPool(2);
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);

        List<TFuture<Integer>> all = pool.invokeAll(tasks);
        List<Integer> results = new ArrayList<>();
        for (TFuture<Integer> f : all) {
            assertThat(f.isDone()).isTrue();
            results.add(f.get());
        }
        assertThat(results).containsExactly(1, 2, 3);

        Integer any = pool.invokeAny(List.of(
                () -> {
                    throw new IllegalStateException("fails");
                },
                () -> 7));
        assertThat(any).isEqualTo(7);

        assertThatThrownBy(() -> pool.invokeAny(Collections.<Callable<Integer>>singletonList(() -> {
                    throw new IllegalStateException("only failure");
                })))
                .isInstanceOf(ExecutionException.class);
        pool.close();
        assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    void timedInvokeAllCancelsWhatDoesNotFinish() throws Exception {
        TExecutorService pool = TExecutors.newFixedThreadPool(1);
        List<Callable<String>> tasks = List.of(() -> "quick", () -> {
            Thread.sleep(10_000);
            return "slow";
        });

        List<TFuture<String>> futures = pool.invokeAll(tasks, 200, TimeUnit.MILLISECONDS);

        assertThat(futures.get(0).get()).isEqualTo("quick");
        assertThat(futures.get(1).isCancelled()).isTrue();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void completionServiceHandsBackResultsInCompletionOrder() throws Exception {
        TExecutorService pool = TExecutors.newFixedThreadPool(3);
        TExecutorCompletionService<String> service = new TExecutorCompletionService<>(pool);
        TCountDownLatch releaseSlow = new TCountDownLatch(1);
        service.submit(() -> {
            releaseSlow.await();
            return "slow";
        });
        service.submit(() -> "fast");

        assertThat(service.take().get()).isEqualTo("fast");
        assertThat(service.poll()).isNull();
        releaseSlow.countDown();
        TFuture<String> next = service.poll(5, TimeUnit.SECONDS);
        assertThat(next).isNotNull();
        assertThat(next.get()).isEqualTo("slow");
        pool.shutdown();
    }

    @Test
    void threadPerTaskExecutorRunsEachTaskOnItsOwnThread() throws Exception {
        TExecutorService executor = TExecutors.newVirtualThreadPerTaskExecutor();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        List<TFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(executor.submit(() -> {
                threads.add(Thread.currentThread());
            }));
        }
        for (TFuture<?> f : futures) {
            f.get();
        }
        executor.close();

        assertThat(executor.isTerminated()).isTrue();
        assertThat(threads).hasSize(4).doesNotHaveDuplicates();
    }

    @Test
    void defaultThreadFactoryNamesItsThreads() {
        Thread t = TExecutors.defaultThreadFactory().newThread(() -> {});
        assertThat(t).isNotNull();
        assertThat(t.getName()).matches("pool-\\d+-thread-1");
        assertThat(t.isDaemon()).isFalse();
    }

    @Test
    void settersValidateTheirArguments() {
        TThreadPoolExecutor pool = new TThreadPoolExecutor(2, 4, 1, TimeUnit.SECONDS, new TLinkedBlockingQueue<>());

        assertThatThrownBy(() -> pool.setCorePoolSize(5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.setMaximumPoolSize(1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pool.prestartAllCoreThreads()).isEqualTo(2);
        assertThat(pool.getPoolSize()).isEqualTo(2);
        pool.setKeepAliveTime(2, TimeUnit.SECONDS);
        assertThat(pool.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(2000);
        pool.shutdown();
    }
}
