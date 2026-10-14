package incari.classlib.substitutes.java.util.concurrent.locks;

import static incari.classlib.substitutes.java.util.concurrent.Concurrency.awaitBlocked;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.runConcurrently;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.substitutes.java.util.concurrent.Concurrency.Worker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ReentrantLockTest {

    private int counter;
    private int inside;

    @Test
    void excludesOtherThreadsUnderContention() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        AtomicBoolean overlapped = new AtomicBoolean();
        runConcurrently(8, () -> {
            for (int i = 0; i < 20_000; i++) {
                lock.lock();
                try {
                    if (++inside != 1) {
                        overlapped.set(true);
                    }
                    counter++;
                    inside--;
                } finally {
                    lock.unlock();
                }
            }
        });
        assertThat(counter).isEqualTo(160_000);
        assertThat(overlapped).isFalse();
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    void fairLockExcludesUnderContention() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock(true);
        runConcurrently(4, () -> {
            for (int i = 0; i < 2_000; i++) {
                lock.lock();
                try {
                    counter++;
                } finally {
                    lock.unlock();
                }
            }
        });
        assertThat(counter).isEqualTo(8_000);
        assertThat(lock.isFair()).isTrue();
    }

    @Test
    void isReentrantAndCountsHolds() {
        TReentrantLock lock = new TReentrantLock();
        assertThat(lock.getHoldCount()).isZero();
        lock.lock();
        lock.lock();
        assertThat(lock.tryLock()).isTrue();
        assertThat(lock.getHoldCount()).isEqualTo(3);
        assertThat(lock.isHeldByCurrentThread()).isTrue();
        assertThat(lock.getOwner()).isSameAs(Thread.currentThread());
        assertThat(lock.toString())
                .contains("[Locked by thread " + Thread.currentThread().getName() + "]");
        lock.unlock();
        lock.unlock();
        assertThat(lock.isLocked()).isTrue();
        lock.unlock();
        assertThat(lock.isLocked()).isFalse();
        assertThat(lock.getOwner()).isNull();
        assertThat(lock.toString()).endsWith("[Unlocked]");
        assertThatThrownBy(lock::unlock).isInstanceOf(IllegalMonitorStateException.class);
    }

    @Test
    void unlockByAnotherThreadFails() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        lock.lock();
        AtomicBoolean threw = new AtomicBoolean();
        start(() -> {
                    try {
                        lock.unlock();
                    } catch (IllegalMonitorStateException e) {
                        threw.set(true);
                    }
                })
                .join();
        assertThat(threw).isTrue();
        assertThat(lock.isHeldByCurrentThread()).isTrue();
        lock.unlock();
    }

    @Test
    void tryLockFailsWhileHeldAndTimesOut() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        lock.lock();
        start(() -> {
                    assertThat(lock.tryLock()).isFalse();
                    assertThat(lock.isHeldByCurrentThread()).isFalse();
                    long started = System.nanoTime();
                    assertThat(lock.tryLock(50, TimeUnit.MILLISECONDS)).isFalse();
                    assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(50));
                    assertThat(lock.tryLock(0, TimeUnit.MILLISECONDS)).isFalse();
                })
                .join();
        assertThat(lock.hasQueuedThreads()).isFalse();
        lock.unlock();
    }

    @Test
    void timedTryLockSucceedsWhenReleased() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        lock.lock();
        AtomicBoolean acquired = new AtomicBoolean();
        Worker waiter = start(() -> {
            acquired.set(lock.tryLock(10, TimeUnit.SECONDS));
            lock.unlock();
        });
        awaitBlocked(waiter.thread());
        assertThat(lock.hasQueuedThread(waiter.thread())).isTrue();
        assertThat(lock.getQueueLength()).isEqualTo(1);
        assertThat(lock.getQueuedThreads()).containsExactly(waiter.thread());
        lock.unlock();
        waiter.join();
        assertThat(acquired).isTrue();
        assertThat(lock.getQueueLength()).isZero();
    }

    @Test
    void lockInterruptiblyThrowsOnInterrupt() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        lock.lock();
        AtomicBoolean interrupted = new AtomicBoolean();
        Worker waiter = start(() -> {
            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                interrupted.set(!Thread.currentThread().isInterrupted());
            }
        });
        awaitBlocked(waiter.thread());
        waiter.thread().interrupt();
        waiter.join();
        assertThat(interrupted).as("threw, with the interrupt status cleared").isTrue();
        assertThat(lock.hasQueuedThreads()).isFalse();
        lock.unlock();
    }

    @Test
    void lockInterruptiblyThrowsIfAlreadyInterrupted() {
        TReentrantLock lock = new TReentrantLock();
        Thread.currentThread().interrupt();
        // Even a free lock: the JDK checks the interrupt first.
        assertThatThrownBy(lock::lockInterruptibly).isInstanceOf(InterruptedException.class);
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    void lockIgnoresInterruptButKeepsIt() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        lock.lock();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Worker waiter = start(() -> {
            lock.lock();
            stillInterrupted.set(Thread.currentThread().isInterrupted());
            lock.unlock();
        });
        awaitBlocked(waiter.thread());
        waiter.thread().interrupt();
        Thread.sleep(20);
        assertThat(waiter.thread().isAlive()).isTrue();
        lock.unlock();
        waiter.join();
        assertThat(stillInterrupted).isTrue();
    }

    @Test
    void fairLockServesWaitersInOrder() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock(true);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        lock.lock();
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int id = i;
            Worker worker = start(() -> {
                lock.lock();
                order.add(id);
                lock.unlock();
            });
            awaitBlocked(worker.thread());
            workers.add(worker);
        }
        assertThat(lock.getQueueLength()).isEqualTo(5);
        lock.unlock();
        for (Worker worker : workers) {
            worker.join();
        }
        assertThat(order).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void conditionPassesItemsBetweenThreads() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        TCondition notEmpty = lock.newCondition();
        TCondition notFull = lock.newCondition();
        int[] slot = {0};
        boolean[] full = {false};
        AtomicInteger sum = new AtomicInteger();
        Worker consumer = start(() -> {
            for (int i = 0; i < 1000; i++) {
                lock.lock();
                try {
                    while (!full[0]) {
                        notEmpty.await();
                    }
                    sum.addAndGet(slot[0]);
                    full[0] = false;
                    notFull.signal();
                } finally {
                    lock.unlock();
                }
            }
        });
        for (int i = 1; i <= 1000; i++) {
            lock.lock();
            try {
                while (full[0]) {
                    notFull.await();
                }
                slot[0] = i;
                full[0] = true;
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }
        consumer.join();
        assertThat(sum.get()).isEqualTo(500_500);
    }

    @Test
    void signalAllWakesEveryWaiter() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        TCondition condition = lock.newCondition();
        boolean[] go = {false};
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            workers.add(start(() -> {
                lock.lock();
                try {
                    while (!go[0]) {
                        condition.await();
                    }
                } finally {
                    lock.unlock();
                }
            }));
        }
        for (Worker worker : workers) {
            awaitBlocked(worker.thread());
        }
        lock.lock();
        try {
            assertThat(lock.hasWaiters(condition)).isTrue();
            assertThat(lock.getWaitQueueLength(condition)).isEqualTo(4);
            assertThat(lock.getWaitingThreads(condition)).hasSize(4);
            go[0] = true;
            condition.signalAll();
            assertThat(lock.hasWaiters(condition)).isFalse();
        } finally {
            lock.unlock();
        }
        for (Worker worker : workers) {
            worker.join();
        }
    }

    @Test
    @SuppressWarnings("JavaUtilDate") // Condition.awaitUntil takes a Date
    void awaitNanosTimesOutAndRestoresHolds() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        TCondition condition = lock.newCondition();
        lock.lock();
        lock.lock();
        long remaining = condition.awaitNanos(TimeUnit.MILLISECONDS.toNanos(30));
        assertThat(remaining).isLessThanOrEqualTo(0);
        assertThat(lock.getHoldCount()).isEqualTo(2);
        assertThat(condition.await(10, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(condition.awaitUntil(new Date(System.currentTimeMillis() + 10)))
                .isFalse();
        assertThat(lock.hasWaiters(condition)).isFalse();
        lock.unlock();
        lock.unlock();
    }

    @Test
    void awaitReleasesTheLockWhileWaiting() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        TCondition condition = lock.newCondition();
        AtomicBoolean signalled = new AtomicBoolean();
        Worker waiter = start(() -> {
            lock.lock();
            try {
                signalled.set(condition.await(10, TimeUnit.SECONDS));
                assertThat(lock.isHeldByCurrentThread()).isTrue();
            } finally {
                lock.unlock();
            }
        });
        awaitBlocked(waiter.thread());
        assertThat(lock.tryLock(10, TimeUnit.SECONDS)).isTrue();
        condition.signal();
        lock.unlock();
        waiter.join();
        assertThat(signalled).isTrue();
    }

    @Test
    void awaitThrowsOnInterruptAndRetakesTheLock() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        TCondition condition = lock.newCondition();
        AtomicBoolean ok = new AtomicBoolean();
        Worker waiter = start(() -> {
            lock.lock();
            try {
                condition.await();
            } catch (InterruptedException e) {
                ok.set(lock.isHeldByCurrentThread());
            } finally {
                lock.unlock();
            }
        });
        awaitBlocked(waiter.thread());
        waiter.thread().interrupt();
        waiter.join();
        assertThat(ok).isTrue();
    }

    @Test
    void awaitUninterruptiblyKeepsWaitingAndTheInterrupt() throws InterruptedException {
        TReentrantLock lock = new TReentrantLock();
        TCondition condition = lock.newCondition();
        AtomicBoolean interrupted = new AtomicBoolean();
        Worker waiter = start(() -> {
            lock.lock();
            try {
                condition.awaitUninterruptibly();
                interrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                lock.unlock();
            }
        });
        awaitBlocked(waiter.thread());
        waiter.thread().interrupt();
        Thread.sleep(20);
        assertThat(waiter.thread().isAlive()).isTrue();
        lock.lock();
        condition.signal();
        lock.unlock();
        waiter.join();
        assertThat(interrupted).isTrue();
    }

    @Test
    void conditionNeedsTheLockAndItsOwner() {
        TReentrantLock lock = new TReentrantLock();
        TCondition condition = lock.newCondition();
        assertThatThrownBy(condition::await).isInstanceOf(IllegalMonitorStateException.class);
        assertThatThrownBy(condition::signal).isInstanceOf(IllegalMonitorStateException.class);
        assertThatThrownBy(() -> lock.hasWaiters(condition)).isInstanceOf(IllegalMonitorStateException.class);
        TCondition foreign = new TReentrantLock().newCondition();
        lock.lock();
        try {
            assertThatThrownBy(() -> lock.getWaitQueueLength(foreign)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            lock.unlock();
        }
    }

    /** Guava's cache segments extend ReentrantLock and use it as their own lock. */
    @Test
    void worksAsASuperclass() {
        final class Segment extends TReentrantLock {
            private static final long serialVersionUID = 1L;
            int value;

            void put(int v) {
                lock();
                try {
                    value = v;
                } finally {
                    unlock();
                }
            }
        }
        Segment segment = new Segment();
        segment.put(3);
        assertThat(segment.value).isEqualTo(3);
        assertThat(segment.isLocked()).isFalse();
    }

    @Test
    void ownableSynchronizerRecordsTheOwner() {
        final class Blocker extends TAbstractOwnableSynchronizer {
            private static final long serialVersionUID = 1L;

            void own() {
                setExclusiveOwnerThread(Thread.currentThread());
            }

            Thread owner() {
                return getExclusiveOwnerThread();
            }
        }
        Blocker blocker = new Blocker();
        assertThat(blocker.owner()).isNull();
        blocker.own();
        assertThat(blocker.owner()).isSameAs(Thread.currentThread());
    }
}
