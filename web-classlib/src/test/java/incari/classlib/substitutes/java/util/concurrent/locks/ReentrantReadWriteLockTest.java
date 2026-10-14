package incari.classlib.substitutes.java.util.concurrent.locks;

import static incari.classlib.substitutes.java.util.concurrent.Concurrency.awaitBlocked;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.runConcurrently;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.substitutes.java.util.concurrent.Concurrency.Worker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ReentrantReadWriteLockTest {

    private int a;
    private int b;

    @Test
    void readersShareTheLock() throws InterruptedException {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        int readers = 6;
        CountDownLatch allInside = new CountDownLatch(readers);
        runConcurrently(readers, () -> {
            lock.readLock().lock();
            try {
                allInside.countDown();
                // Only returns if every reader holds the read lock at once.
                assertThat(allInside.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                lock.readLock().unlock();
            }
        });
        assertThat(lock.getReadLockCount()).isZero();
    }

    @Test
    void writersExcludeReadersAndEachOther() throws InterruptedException {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        AtomicBoolean torn = new AtomicBoolean();
        AtomicInteger thread = new AtomicInteger();
        runConcurrently(8, () -> {
            boolean writer = thread.getAndIncrement() % 2 == 0;
            for (int i = 0; i < 5_000; i++) {
                if (writer) {
                    lock.writeLock().lock();
                    try {
                        a++;
                        b++;
                    } finally {
                        lock.writeLock().unlock();
                    }
                } else {
                    lock.readLock().lock();
                    try {
                        if (a != b) {
                            torn.set(true);
                        }
                    } finally {
                        lock.readLock().unlock();
                    }
                }
            }
        });
        assertThat(a).isEqualTo(20_000);
        assertThat(torn).isFalse();
    }

    @Test
    void countsHolds() {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        lock.readLock().lock();
        lock.readLock().lock();
        assertThat(lock.getReadHoldCount()).isEqualTo(2);
        assertThat(lock.getReadLockCount()).isEqualTo(2);
        assertThat(lock.readLock().toString()).endsWith("[Read locks = 2]");
        lock.readLock().unlock();
        lock.readLock().unlock();
        assertThatThrownBy(() -> lock.readLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);

        lock.writeLock().lock();
        lock.writeLock().lock();
        assertThat(lock.getWriteHoldCount()).isEqualTo(2);
        assertThat(lock.writeLock().getHoldCount()).isEqualTo(2);
        assertThat(lock.isWriteLocked()).isTrue();
        assertThat(lock.isWriteLockedByCurrentThread()).isTrue();
        assertThat(lock.writeLock().isHeldByCurrentThread()).isTrue();
        assertThat(lock.getOwner()).isSameAs(Thread.currentThread());
        assertThat(lock.toString()).endsWith("[Write locks = 2, Read locks = 0]");
        lock.writeLock().unlock();
        lock.writeLock().unlock();
        assertThat(lock.isWriteLocked()).isFalse();
        assertThat(lock.writeLock().toString()).endsWith("[Unlocked]");
        assertThatThrownBy(() -> lock.writeLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);
    }

    @Test
    void writeLockDowngradesToReadLock() throws InterruptedException {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        lock.writeLock().lock();
        lock.readLock().lock();
        lock.writeLock().unlock();
        assertThat(lock.isWriteLocked()).isFalse();
        assertThat(lock.getReadHoldCount()).isEqualTo(1);
        start(() -> {
                    assertThat(lock.readLock().tryLock()).isTrue();
                    lock.readLock().unlock();
                    assertThat(lock.writeLock().tryLock()).isFalse();
                })
                .join();
        lock.readLock().unlock();
    }

    @Test
    void readerCannotUpgrade() {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        lock.readLock().lock();
        assertThat(lock.writeLock().tryLock()).isFalse();
        lock.readLock().unlock();
        assertThat(lock.writeLock().tryLock()).isTrue();
        lock.writeLock().unlock();
    }

    @Test
    void writerWaitsForReadersThenGetsTheLock() throws InterruptedException {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        lock.readLock().lock();
        Worker writer = start(() -> {
            lock.writeLock().lock();
            lock.writeLock().unlock();
        });
        awaitBlocked(writer.thread());
        assertThat(lock.hasQueuedThread(writer.thread())).isTrue();
        assertThat(lock.getQueuedWriterThreads()).containsExactly(writer.thread());
        assertThat(lock.getQueuedReaderThreads()).isEmpty();
        // A queued writer holds back new readers (so writers aren't starved), but not re-entrant ones.
        start(() -> assertThat(lock.readLock().tryLock(30, TimeUnit.MILLISECONDS))
                        .isFalse())
                .join();
        lock.readLock().lock();
        lock.readLock().unlock();
        lock.readLock().unlock();
        writer.join();
        assertThat(lock.hasQueuedThreads()).isFalse();
    }

    @Test
    void timedAndInterruptibleAcquiresGiveUp() throws InterruptedException {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock(true);
        lock.writeLock().lock();
        start(() -> {
                    assertThat(lock.readLock().tryLock(20, TimeUnit.MILLISECONDS))
                            .isFalse();
                    assertThat(lock.writeLock().tryLock(20, TimeUnit.MILLISECONDS))
                            .isFalse();
                })
                .join();
        AtomicBoolean interrupted = new AtomicBoolean();
        Worker waiter = start(() -> {
            try {
                lock.readLock().lockInterruptibly();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        awaitBlocked(waiter.thread());
        waiter.thread().interrupt();
        waiter.join();
        assertThat(interrupted).isTrue();
        assertThat(lock.getQueueLength()).isZero();
        lock.writeLock().unlock();
    }

    @Test
    void writeLockConditionWorks() throws InterruptedException {
        TReentrantReadWriteLock lock = new TReentrantReadWriteLock();
        TCondition condition = lock.writeLock().newCondition();
        boolean[] ready = {false};
        Worker waiter = start(() -> {
            lock.writeLock().lock();
            try {
                while (!ready[0]) {
                    condition.await();
                }
            } finally {
                lock.writeLock().unlock();
            }
        });
        awaitBlocked(waiter.thread());
        lock.writeLock().lock();
        try {
            assertThat(lock.getWaitQueueLength(condition)).isEqualTo(1);
            ready[0] = true;
            condition.signal();
        } finally {
            lock.writeLock().unlock();
        }
        waiter.join();
        assertThatThrownBy(() -> lock.readLock().newCondition()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** Guava's cycle-detecting locks subclass the lock and both of its views. */
    @Test
    void nestedLocksCanBeSubclassed() {
        final class Lock extends TReentrantReadWriteLock {
            private static final long serialVersionUID = 1L;
        }
        final class Read extends TReentrantReadWriteLock.ReadLock {
            private static final long serialVersionUID = 1L;

            Read(TReentrantReadWriteLock lock) {
                super(lock);
            }
        }
        final class Write extends TReentrantReadWriteLock.WriteLock {
            private static final long serialVersionUID = 1L;

            Write(TReentrantReadWriteLock lock) {
                super(lock);
            }
        }
        Lock lock = new Lock();
        Read read = new Read(lock);
        Write write = new Write(lock);
        write.lock();
        assertThat(lock.isWriteLockedByCurrentThread()).isTrue();
        read.lock();
        write.unlock();
        assertThat(lock.getReadHoldCount()).isEqualTo(1);
        lock.readLock().unlock();
        assertThat(lock.getReadLockCount()).isZero();
    }
}
