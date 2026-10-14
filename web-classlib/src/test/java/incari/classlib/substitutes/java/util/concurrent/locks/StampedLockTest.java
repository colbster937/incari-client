package incari.classlib.substitutes.java.util.concurrent.locks;

import static incari.classlib.substitutes.java.util.concurrent.Concurrency.awaitBlocked;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.runConcurrently;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.substitutes.java.util.concurrent.Concurrency.Worker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class StampedLockTest {

    private int counter;

    @Test
    void writeLockExcludesUnderContention() throws InterruptedException {
        TStampedLock lock = new TStampedLock();
        runConcurrently(8, () -> {
            for (int i = 0; i < 10_000; i++) {
                long stamp = lock.writeLock();
                try {
                    counter++;
                } finally {
                    lock.unlockWrite(stamp);
                }
            }
        });
        assertThat(counter).isEqualTo(80_000);
        assertThat(lock.isWriteLocked()).isFalse();
    }

    @Test
    void stampsSayWhatTheyAre() {
        TStampedLock lock = new TStampedLock();
        long optimistic = lock.tryOptimisticRead();
        assertThat(TStampedLock.isOptimisticReadStamp(optimistic)).isTrue();
        assertThat(TStampedLock.isLockStamp(optimistic)).isFalse();
        long read = lock.readLock();
        assertThat(TStampedLock.isReadLockStamp(read)).isTrue();
        assertThat(TStampedLock.isLockStamp(read)).isTrue();
        lock.unlockRead(read);
        long write = lock.writeLock();
        assertThat(TStampedLock.isWriteLockStamp(write)).isTrue();
        assertThat(lock.toString()).endsWith("[Write-locked]");
        lock.unlock(write);
        assertThat(lock.toString()).endsWith("[Unlocked]");
        assertThat(TStampedLock.isOptimisticReadStamp(0L)).isFalse();
    }

    @Test
    void optimisticReadValidatesUntilAWrite() {
        TStampedLock lock = new TStampedLock();
        long stamp = lock.tryOptimisticRead();
        assertThat(stamp).isNotZero();
        assertThat(lock.validate(stamp)).isTrue();
        long read = lock.readLock();
        assertThat(lock.validate(stamp)).as("reads don't invalidate").isTrue();
        lock.unlockRead(read);
        long write = lock.writeLock();
        assertThat(lock.validate(stamp)).isFalse();
        assertThat(lock.validate(write)).isTrue();
        assertThat(lock.tryOptimisticRead())
                .as("no optimistic read while written")
                .isZero();
        lock.unlockWrite(write);
        assertThat(lock.validate(stamp)).isFalse();
        assertThat(lock.validate(write)).isFalse();
        assertThat(lock.validate(0L)).isFalse();
    }

    @Test
    void readersShareAndBlockWriters() throws InterruptedException {
        TStampedLock lock = new TStampedLock();
        long first = lock.readLock();
        long second = lock.tryReadLock();
        assertThat(second).isNotZero();
        assertThat(lock.getReadLockCount()).isEqualTo(2);
        assertThat(lock.isReadLocked()).isTrue();
        assertThat(lock.tryWriteLock()).isZero();
        start(() -> assertThat(lock.tryWriteLock(20, TimeUnit.MILLISECONDS)).isZero())
                .join();
        Worker writer = start(() -> lock.unlockWrite(lock.writeLock()));
        awaitBlocked(writer.thread());
        lock.unlockRead(first);
        lock.unlockRead(second);
        writer.join();
        assertThat(lock.isReadLocked()).isFalse();
    }

    @Test
    void wrongStampsAreRejected() {
        TStampedLock lock = new TStampedLock();
        long read = lock.readLock();
        assertThatThrownBy(() -> lock.unlockWrite(read)).isInstanceOf(IllegalMonitorStateException.class);
        lock.unlockRead(read);
        assertThatThrownBy(() -> lock.unlockRead(read)).isInstanceOf(IllegalMonitorStateException.class);
        long write = lock.writeLock();
        lock.unlockWrite(write);
        long again = lock.writeLock();
        assertThatThrownBy(() -> lock.unlockWrite(write)).isInstanceOf(IllegalMonitorStateException.class);
        lock.unlockWrite(again);
    }

    @Test
    void convertsBetweenModes() {
        TStampedLock lock = new TStampedLock();
        long read = lock.readLock();
        long write = lock.tryConvertToWriteLock(read);
        assertThat(TStampedLock.isWriteLockStamp(write)).isTrue();
        assertThat(lock.isReadLocked()).isFalse();
        long downgraded = lock.tryConvertToReadLock(write);
        assertThat(TStampedLock.isReadLockStamp(downgraded)).isTrue();
        assertThat(lock.isWriteLocked()).isFalse();
        long other = lock.readLock();
        assertThat(lock.tryConvertToWriteLock(downgraded))
                .as("not the only reader")
                .isZero();
        lock.unlockRead(other);
        long optimistic = lock.tryConvertToOptimisticRead(downgraded);
        assertThat(TStampedLock.isOptimisticReadStamp(optimistic)).isTrue();
        assertThat(lock.isReadLocked()).isFalse();
        long fromOptimistic = lock.tryConvertToWriteLock(optimistic);
        assertThat(fromOptimistic).isNotZero();
        assertThat(lock.validate(optimistic)).isFalse();
        assertThat(lock.tryUnlockWrite()).isTrue();
        assertThat(lock.tryUnlockWrite()).isFalse();
        assertThat(lock.tryUnlockRead()).isFalse();
    }

    @Test
    void writeLockInterruptiblyThrows() throws InterruptedException {
        TStampedLock lock = new TStampedLock();
        long stamp = lock.writeLock();
        AtomicBoolean interrupted = new AtomicBoolean();
        Worker waiter = start(() -> {
            try {
                lock.writeLockInterruptibly();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        awaitBlocked(waiter.thread());
        waiter.thread().interrupt();
        waiter.join();
        assertThat(interrupted).isTrue();
        lock.unlockWrite(stamp);
        assertThat(lock.tryReadLock()).as("readers aren't held back any more").isNotZero();
    }

    @Test
    void viewsActAsLocks() throws InterruptedException {
        TStampedLock lock = new TStampedLock();
        TReadWriteLock view = lock.asReadWriteLock();
        view.readLock().lock();
        assertThat(lock.isReadLocked()).isTrue();
        assertThat(view.writeLock().tryLock()).isFalse();
        view.readLock().unlock();
        assertThat(view.writeLock().tryLock(1, TimeUnit.SECONDS)).isTrue();
        assertThat(lock.isWriteLocked()).isTrue();
        view.writeLock().unlock();
        assertThatThrownBy(() -> lock.asWriteLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);
        assertThatThrownBy(() -> lock.asReadLock().newCondition()).isInstanceOf(UnsupportedOperationException.class);
    }
}
