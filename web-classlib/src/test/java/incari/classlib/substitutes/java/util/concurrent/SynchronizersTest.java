package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class SynchronizersTest {

    @Test
    void countDownLatchReleasesWaitersAtZero() throws Exception {
        TCountDownLatch latch = new TCountDownLatch(3);
        AtomicInteger released = new AtomicInteger();
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    latch.await();
                    released.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiters.add(t);
            t.start();
        }
        assertThat(latch.await(10, TimeUnit.MILLISECONDS)).isFalse();
        latch.countDown();
        latch.countDown();
        assertThat(latch.getCount()).isEqualTo(1);
        latch.countDown();
        latch.countDown(); // no effect below zero
        for (Thread t : waiters) {
            t.join();
        }

        assertThat(released).hasValue(2);
        assertThat(latch.getCount()).isZero();
        assertThat(latch.await(0, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> new TCountDownLatch(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void semaphoreLimitsConcurrency() throws Exception {
        TSemaphore semaphore = new TSemaphore(2);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> {
                semaphore.acquireUninterruptibly();
                try {
                    int now = inside.incrementAndGet();
                    maxInside.accumulateAndGet(now, Math::max);
                    Thread.sleep(10);
                    inside.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    semaphore.release();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(maxInside.get()).isBetween(1, 2);
        assertThat(semaphore.availablePermits()).isEqualTo(2);
    }

    @Test
    void semaphoreTryAcquireAndDrain() throws Exception {
        TSemaphore semaphore = new TSemaphore(3, true);
        assertThat(semaphore.isFair()).isTrue();
        assertThat(semaphore.tryAcquire(2)).isTrue();
        assertThat(semaphore.tryAcquire(2)).isFalse();
        assertThat(semaphore.tryAcquire(2, 20, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(semaphore.drainPermits()).isEqualTo(1);
        assertThat(semaphore.availablePermits()).isZero();

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            semaphore.release(2);
        });
        releaser.start();
        assertThat(semaphore.tryAcquire(2, 5, TimeUnit.SECONDS)).isTrue();
        releaser.join();
        assertThat(semaphore.hasQueuedThreads()).isFalse();
    }

    @Test
    void fairSemaphoreServesWaitersInArrivalOrder() throws Exception {
        TSemaphore semaphore = new TSemaphore(0, true);
        List<Integer> order = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int id = i;
            Thread t = new Thread(() -> {
                semaphore.acquireUninterruptibly();
                order.add(id);
            });
            threads.add(t);
            t.start();
            while (semaphore.getQueueLength() < i + 1) {
                Thread.sleep(1);
            }
        }
        for (int i = 0; i < 3; i++) {
            semaphore.release();
            while (order.size() < i + 1) {
                Thread.sleep(1);
            }
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(order).containsExactly(0, 1, 2);
    }

    @Test
    void semaphoreAcquireIsInterruptible() throws Exception {
        TSemaphore semaphore = new TSemaphore(0);
        AtomicInteger interrupted = new AtomicInteger();
        Thread t = new Thread(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });
        t.start();
        while (!semaphore.hasQueuedThreads()) {
            Thread.sleep(1);
        }
        t.interrupt();
        t.join();
        assertThat(interrupted).hasValue(1);
        assertThat(semaphore.getQueueLength()).isZero();
    }

    @Test
    void cyclicBarrierTripsRepeatedlyAndRunsItsAction() throws Exception {
        AtomicInteger trips = new AtomicInteger();
        TCyclicBarrier barrier = new TCyclicBarrier(3, trips::incrementAndGet);
        List<Integer> indices = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    for (int round = 0; round < 2; round++) {
                        indices.add(barrier.await());
                    }
                } catch (InterruptedException | TBrokenBarrierException e) {
                    throw new AssertionError(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(trips).hasValue(2);
        assertThat(indices).containsExactlyInAnyOrder(0, 1, 2, 0, 1, 2);
        assertThat(barrier.isBroken()).isFalse();
        assertThat(barrier.getParties()).isEqualTo(3);
    }

    @Test
    void cyclicBarrierBreaksOnTimeout() throws Exception {
        TCyclicBarrier barrier = new TCyclicBarrier(2);

        assertThatThrownBy(() -> barrier.await(20, TimeUnit.MILLISECONDS)).isInstanceOf(TTimeoutException.class);
        assertThat(barrier.isBroken()).isTrue();
        assertThatThrownBy(barrier::await).isInstanceOf(TBrokenBarrierException.class);

        barrier.reset();
        assertThat(barrier.isBroken()).isFalse();
        assertThat(barrier.getNumberWaiting()).isZero();
    }
}
