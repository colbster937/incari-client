package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real-thread helpers for the concurrency tests (these run on a JVM, with true parallelism). */
public final class Concurrency {

    private static final long TIMEOUT_MILLIS = 10_000;

    private Concurrency() {}

    /** A body that may throw, run on its own thread. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /** A started thread whose failure, if any, {@link #join} rethrows. */
    public static final class Worker {
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Worker(Body body) {
            thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.setDaemon(true);
        }

        public Thread thread() {
            return thread;
        }

        public void join() throws InterruptedException {
            thread.join(TIMEOUT_MILLIS);
            assertThat(thread.isAlive()).as("worker finished in time").isFalse();
            Throwable t = failure.get();
            if (t != null) {
                throw new AssertionError("worker failed", t);
            }
        }
    }

    public static Worker start(Body body) {
        Worker worker = new Worker(body);
        worker.thread.start();
        return worker;
    }

    /** Runs {@code body} on {@code count} threads released together, and waits for them all. */
    public static void runConcurrently(int count, Body body) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            workers.add(start(() -> {
                go.await();
                body.run();
            }));
        }
        go.countDown();
        for (Worker worker : workers) {
            worker.join();
        }
    }

    /** Polls until {@code condition} holds; test-only, so polling is fine here. */
    public static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline)
                    .as("condition held in time")
                    .isNegative();
            Thread.sleep(1);
        }
    }

    /** Waits until {@code thread} is blocked (waiting on a monitor, as everything here blocks). */
    public static void awaitBlocked(Thread thread) throws InterruptedException {
        eventually(() -> {
            Thread.State state = thread.getState();
            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        });
    }
}
