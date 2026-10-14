package incari.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IdleTimeExecutorTest {

    /** A clock each task moves on by its cost, and a count of the slots asked for. */
    private static final class FakeSlots implements IdleTimeExecutor.Slots {
        double now;
        int armed;

        @Override
        public void arm() {
            armed++;
        }

        @Override
        public double await() {
            throw new UnsupportedOperationException("the tests run slots themselves");
        }

        @Override
        public double now() {
            return now;
        }
    }

    private final FakeSlots slots = new FakeSlots();
    private final List<Runnable> workers = new ArrayList<>();
    private final IdleTimeExecutor executor = new IdleTimeExecutor(slots, workers::add);
    private final List<String> ran = new ArrayList<>();

    private Runnable task(String name, double millis) {
        return () -> {
            ran.add(name);
            slots.now += millis;
        };
    }

    @Test
    void submittingOnlyQueuesAndAsksForASlot() {
        executor.execute(task("a", 1));

        assertThat(ran).isEmpty();
        assertThat(executor.queued()).isEqualTo(1);
        assertThat(slots.armed).isEqualTo(1);
        assertThat(workers).hasSize(1);
    }

    @Test
    void theWorkerStartsOnce() {
        executor.execute(task("a", 1));
        executor.execute(task("b", 1));

        assertThat(workers).hasSize(1);
    }

    @Test
    void aSlotRunsTasksInOrderUntilItsDeadline() {
        for (String name : List.of("a", "b", "c", "d")) {
            executor.execute(task(name, 3));
        }

        assertThat(executor.runUntil(5)).isEqualTo(2);
        assertThat(ran).containsExactly("a", "b");
        assertThat(executor.queued()).isEqualTo(2);
    }

    @Test
    void aSlotRunsAtLeastOneTaskEvenPastItsDeadline() {
        slots.now = 10;
        executor.execute(task("a", 3));
        executor.execute(task("b", 3));

        assertThat(executor.runUntil(5)).isEqualTo(1);
        assertThat(ran).containsExactly("a");
    }

    @Test
    void tasksQueuedDuringASlotRunInIt() {
        executor.execute(() -> {
            ran.add("first");
            executor.execute(task("resubmitted", 1));
        });

        assertThat(executor.runUntil(100)).isEqualTo(2);
        assertThat(ran).containsExactly("first", "resubmitted");
    }

    @Test
    void aFailingTaskGoesToTheUncaughtHandlerAndTheSlotGoesOn() {
        List<Throwable> caught = new ArrayList<>();
        Thread thread = Thread.currentThread();
        Thread.@Nullable UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((t, e) -> caught.add(e));
        try {
            executor.execute(() -> {
                throw new IllegalStateException("broken");
            });
            executor.execute(task("after", 1));

            executor.runUntil(100);
        } finally {
            thread.setUncaughtExceptionHandler(previous);
        }

        assertThat(caught).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(ran).containsExactly("after");
    }

    @Test
    void futuresCompleteWhenTheirSlotRuns() throws Exception {
        Future<String> future = executor.submit(() -> "done");

        assertThat(future.isDone()).isFalse();
        executor.runUntil(100);
        assertThat(future.get(0, TimeUnit.SECONDS)).isEqualTo("done");
    }

    @Test
    void shutdownRejectsNewTasksAndNeverWaits() {
        executor.execute(task("a", 1));
        executor.shutdown();

        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isFalse();
        assertThatThrownBy(() -> executor.execute(task("b", 1))).isInstanceOf(RejectedExecutionException.class);
        assertThat(executor.shutdownNow()).hasSize(1);
    }

    @Test
    void anUnusedPoolTerminatesAtShutdown() {
        executor.shutdown();

        assertThat(executor.isTerminated()).isTrue();
        assertThat(workers).isEmpty();
    }

    /** Slots that come at once, each 10 ms long, on the real clock: for runners on real threads. */
    private static final class ImmediateSlots implements IdleTimeExecutor.Slots {
        @Override
        public void arm() {}

        @Override
        public double await() {
            return now() + 10;
        }

        @Override
        public double now() {
            return System.nanoTime() / 1e6;
        }
    }

    @Test
    void aTaskWaitingOutsideLetsTheOthersRunAndGoesOnAfter() throws Exception {
        List<Thread> threads = java.util.Collections.synchronizedList(new ArrayList<>());
        IdleTimeExecutor pool = new IdleTimeExecutor(new ImmediateSlots(), work -> {
            Thread thread = new Thread(work, "runner");
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        });
        java.util.concurrent.CountDownLatch outsideDone = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch othersRan = new java.util.concurrent.CountDownLatch(2);
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean overlapped = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable exclusive = () -> {
            if (running.incrementAndGet() > 1) {
                overlapped.set(true);
            }
        };
        Runnable done = running::decrementAndGet;

        pool.execute(() -> {
            exclusive.run();
            order.add("waiting");
            done.run();
            String answer = IdleTimeExecutor.awaitOutside(() -> {
                try {
                    // The others run while this waits.
                    assertThat(othersRan.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return "answer";
            });
            exclusive.run();
            order.add("after " + answer);
            done.run();
            outsideDone.countDown();
        });
        for (String name : List.of("b", "c")) {
            pool.execute(() -> {
                exclusive.run();
                order.add(name);
                done.run();
                othersRan.countDown();
            });
        }

        assertThat(outsideDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("waiting", "b", "c", "after answer");
        assertThat(overlapped).isFalse();
        assertThat(pool.runners()).isEqualTo(2);
        pool.shutdown();
    }

    @Test
    void aTaskThatBlocksOnWorkQueuedBehindItGivesThePoolUp() throws Exception {
        IdleTimeExecutor pool = new IdleTimeExecutor(new ImmediateSlots(), work -> {
            Thread thread = new Thread(work, "runner");
            thread.setDaemon(true);
            thread.start();
        });
        java.util.concurrent.CountDownLatch inner = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch outerDone = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch afterRan = new java.util.concurrent.CountDownLatch(1);
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

        pool.execute(() -> {
            order.add("outer");
            // What it waits for is queued behind it, as world generation's structure placement
            // joins work it queued itself.
            pool.execute(() -> {
                order.add("inner");
                inner.countDown();
            });
            try {
                // In the page, web-classlib's Waits calls this before the wait.
                IdleTimeExecutor.beforeWait();
                assertThat(inner.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            order.add("outer done");
            outerDone.countDown();
        });

        assertThat(outerDone.await(5, TimeUnit.SECONDS)).isTrue();
        // The runner that gave the pool up queues for it again, and the pool goes on.
        pool.execute(() -> {
            order.add("after");
            afterRan.countDown();
        });
        assertThat(afterRan.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("outer", "inner", "outer done", "after");
        assertThat(pool.runners()).isEqualTo(2);
        pool.shutdown();
    }

    @Test
    void waitingOutsideThePoolChangesNothing() {
        IdleTimeExecutor.beforeWait();

        assertThat(executor.runners()).isZero();
    }

    /** Slots on a clock the test moves: each await is a new slot, 10 ms long, counted. */
    private static final class CountedSlots implements IdleTimeExecutor.Slots {
        private double now;
        final java.util.concurrent.atomic.AtomicInteger slots = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void arm() {}

        @Override
        public synchronized double await() {
            slots.incrementAndGet();
            now += 20;
            return now + 10;
        }

        @Override
        public synchronized double now() {
            return now;
        }

        synchronized void advance(double millis) {
            now += millis;
        }
    }

    @Test
    void aLongTaskPausesForTheNextSlotWhenItsSlotIsOverAndKeepsThePool() throws Exception {
        CountedSlots counted = new CountedSlots();
        IdleTimeExecutor pool = new IdleTimeExecutor(counted, work -> {
            Thread thread = new Thread(work, "runner");
            thread.setDaemon(true);
            thread.start();
        });
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(2);
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Integer> slotOfPart = java.util.Collections.synchronizedList(new ArrayList<>());
        pool.execute(() -> {
            for (int part = 0; part < 3; part++) {
                slotOfPart.add(counted.slots.get());
                order.add("part " + part);
                // Each part takes 8 ms of a 10 ms slot: the second ends past the slot and pauses.
                counted.advance(8);
                IdleTimeExecutor.pauseIfLate();
            }
            finished.countDown();
        });
        pool.execute(() -> {
            order.add("next");
            finished.countDown();
        });

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        // The long task kept the pool through its pause, and its last part ran in the next slot.
        assertThat(order).containsExactly("part 0", "part 1", "part 2", "next");
        assertThat(slotOfPart).containsExactly(1, 1, 2);
        pool.shutdown();
    }

    @Test
    void pausingOutsideThePoolReturnsAtOnce() {
        IdleTimeExecutor.pauseIfLate();
    }
}
