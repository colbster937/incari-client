package incari.classlib.substitutes.java.util.concurrent.locks;

import incari.classlib.substitutes.java.util.concurrent.locks.TLockSupport.Monitors;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * {@code java.util.concurrent.locks.StampedLock}, built on a private monitor: a non-reentrant
 * write lock, a shared read lock and optimistic reads. Blocked threads {@code wait()} on the
 * monitor, which in the browser suspends their coroutine.
 *
 * <p>A stamp is a version number shifted left two bits, with the mode (optimistic, read, write) in
 * those bits. The version goes up by one when the write lock is taken and again when it's
 * released, so it's odd exactly while written, and a stamp validates while the version it carries
 * is current. New readers wait while a writer is queued, so writers aren't starved.
 *
 * <p>{@link #validate} reads the version through a volatile field. On a JVM, that doesn't order
 * the optimistic reads before it the way the JDK's fence does; in the browser nothing is reordered.
 */
public class TStampedLock implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final long OPTIMISTIC = 0L;
    private static final long READ = 1L;
    private static final long WRITE = 2L;
    private static final long MODE_MASK = 3L;
    private static final int MAX_READERS = Integer.MAX_VALUE;

    private final Sync sync = new Sync();

    public TStampedLock() {}

    public long writeLock() {
        try {
            return sync.acquireWrite(false, false, 0L);
        } catch (InterruptedException e) {
            throw new AssertionError("uninterruptible lock was interrupted", e);
        }
    }

    public long tryWriteLock() {
        synchronized (sync) {
            return sync.tryWrite();
        }
    }

    public long tryWriteLock(long time, TimeUnit unit) throws InterruptedException {
        return sync.acquireWrite(true, true, unit.toNanos(time));
    }

    public long writeLockInterruptibly() throws InterruptedException {
        return sync.acquireWrite(true, false, 0L);
    }

    public long readLock() {
        try {
            return sync.acquireRead(false, false, 0L);
        } catch (InterruptedException e) {
            throw new AssertionError("uninterruptible lock was interrupted", e);
        }
    }

    public long tryReadLock() {
        synchronized (sync) {
            return sync.tryRead(false);
        }
    }

    public long tryReadLock(long time, TimeUnit unit) throws InterruptedException {
        return sync.acquireRead(true, true, unit.toNanos(time));
    }

    public long readLockInterruptibly() throws InterruptedException {
        return sync.acquireRead(true, false, 0L);
    }

    /** A stamp to validate later, or zero if the write lock is held. */
    public long tryOptimisticRead() {
        long version = sync.version;
        return isWritten(version) ? 0L : stamp(version, OPTIMISTIC);
    }

    /** Whether the write lock hasn't been taken since {@code stamp} was issued (or is still held by it). */
    public boolean validate(long stamp) {
        return stamp != 0L && versionOf(stamp) == sync.version;
    }

    public void unlockWrite(long stamp) {
        synchronized (sync) {
            if (!isWriteLockStamp(stamp) || !sync.holdsWrite(stamp)) {
                throw new IllegalMonitorStateException();
            }
            sync.releaseWrite();
        }
    }

    public void unlockRead(long stamp) {
        synchronized (sync) {
            if (!isReadLockStamp(stamp) || !sync.holdsRead(stamp)) {
                throw new IllegalMonitorStateException();
            }
            sync.releaseRead();
        }
    }

    public void unlock(long stamp) {
        if (isWriteLockStamp(stamp)) {
            unlockWrite(stamp);
        } else {
            unlockRead(stamp);
        }
    }

    public long tryConvertToWriteLock(long stamp) {
        synchronized (sync) {
            if (isWriteLockStamp(stamp)) {
                return sync.holdsWrite(stamp) ? stamp : 0L;
            }
            if (isReadLockStamp(stamp)) {
                // Only the sole reader can upgrade in place.
                if (!sync.holdsRead(stamp) || sync.readers != 1) {
                    return 0L;
                }
                sync.readers = 0;
                return sync.tryWrite();
            }
            if (stamp == 0L || versionOf(stamp) != sync.version) {
                return 0L;
            }
            return sync.tryWrite();
        }
    }

    public long tryConvertToReadLock(long stamp) {
        synchronized (sync) {
            if (isReadLockStamp(stamp)) {
                return sync.holdsRead(stamp) ? stamp : 0L;
            }
            if (isWriteLockStamp(stamp)) {
                if (!sync.holdsWrite(stamp)) {
                    return 0L;
                }
                sync.releaseWrite();
                return sync.tryRead(false);
            }
            if (stamp == 0L || versionOf(stamp) != sync.version) {
                return 0L;
            }
            return sync.tryRead(false);
        }
    }

    public long tryConvertToOptimisticRead(long stamp) {
        synchronized (sync) {
            if (isWriteLockStamp(stamp)) {
                if (!sync.holdsWrite(stamp)) {
                    return 0L;
                }
                sync.releaseWrite();
            } else if (isReadLockStamp(stamp)) {
                if (!sync.holdsRead(stamp)) {
                    return 0L;
                }
                sync.releaseRead();
            } else if (stamp == 0L || versionOf(stamp) != sync.version) {
                return 0L;
            }
            return stamp(sync.version, OPTIMISTIC);
        }
    }

    /** Releases the write lock whatever the stamp, for recovery after errors. */
    public boolean tryUnlockWrite() {
        synchronized (sync) {
            if (!isWritten(sync.version)) {
                return false;
            }
            sync.releaseWrite();
            return true;
        }
    }

    /** Releases one read hold whatever the stamp, for recovery after errors. */
    public boolean tryUnlockRead() {
        synchronized (sync) {
            if (sync.readers == 0) {
                return false;
            }
            sync.releaseRead();
            return true;
        }
    }

    public boolean isWriteLocked() {
        return isWritten(sync.version);
    }

    public boolean isReadLocked() {
        synchronized (sync) {
            return sync.readers > 0;
        }
    }

    public int getReadLockCount() {
        synchronized (sync) {
            return sync.readers;
        }
    }

    public static boolean isWriteLockStamp(long stamp) {
        return (stamp & MODE_MASK) == WRITE;
    }

    public static boolean isReadLockStamp(long stamp) {
        return (stamp & MODE_MASK) == READ;
    }

    public static boolean isLockStamp(long stamp) {
        return (stamp & MODE_MASK) != OPTIMISTIC;
    }

    public static boolean isOptimisticReadStamp(long stamp) {
        return stamp != 0L && (stamp & MODE_MASK) == OPTIMISTIC;
    }

    public TLock asReadLock() {
        return new ReadLockView();
    }

    public TLock asWriteLock() {
        return new WriteLockView();
    }

    public TReadWriteLock asReadWriteLock() {
        return new TReadWriteLock() {
            @Override
            public TLock readLock() {
                return asReadLock();
            }

            @Override
            public TLock writeLock() {
                return asWriteLock();
            }
        };
    }

    @Override
    public String toString() {
        String state;
        synchronized (sync) {
            if (isWritten(sync.version)) {
                state = "[Write-locked]";
            } else if (sync.readers > 0) {
                state = "[Read-locks:" + sync.readers + "]";
            } else {
                state = "[Unlocked]";
            }
        }
        return super.toString() + state;
    }

    private static boolean isWritten(long version) {
        return (version & 1L) != 0L;
    }

    private static long stamp(long version, long mode) {
        return (version << 2) | mode;
    }

    private static long versionOf(long stamp) {
        return stamp >>> 2;
    }

    /** The lock's state, guarded by this object's monitor except for the volatile version. */
    private static final class Sync implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Odd while write-locked. Starts above zero so no stamp is ever zero. */
        volatile long version = 2L;

        transient int readers;
        transient int waitingWriters;

        long tryWrite() {
            if (isWritten(version) || readers > 0) {
                return 0L;
            }
            long written = version + 1;
            version = written;
            return stamp(written, WRITE);
        }

        /** {@code politely}: leave the lock to a queued writer, as blocking reads do. */
        long tryRead(boolean politely) {
            if (isWritten(version) || (politely && waitingWriters > 0)) {
                return 0L;
            }
            if (readers == MAX_READERS) {
                throw new Error("Maximum lock count exceeded");
            }
            readers++;
            return stamp(version, READ);
        }

        boolean holdsWrite(long stamp) {
            return isWritten(version) && versionOf(stamp) == version;
        }

        boolean holdsRead(long stamp) {
            return readers > 0 && versionOf(stamp) == version;
        }

        void releaseWrite() {
            // Callers hold the monitor, so this read-then-write can't race another writer.
            long written = version;
            version = written + 1;
            notifyAll();
        }

        void releaseRead() {
            if (--readers == 0) {
                notifyAll();
            }
        }

        /** Returns the stamp, or zero on timeout. */
        synchronized long acquireWrite(boolean interruptible, boolean timed, long nanos) throws InterruptedException {
            // Interruptible acquires check first, even for a free lock, as the JDK's do.
            if (interruptible && Thread.interrupted()) {
                throw new InterruptedException();
            }
            long stamp = tryWrite();
            if (stamp != 0L) {
                return stamp;
            }
            waitingWriters++;
            try {
                return await(true, interruptible, timed, nanos);
            } finally {
                // Readers held back for this writer may go now, whether or not it got the lock.
                if (--waitingWriters == 0) {
                    notifyAll();
                }
            }
        }

        synchronized long acquireRead(boolean interruptible, boolean timed, long nanos) throws InterruptedException {
            // Interruptible acquires check first, even for a free lock, as the JDK's do.
            if (interruptible && Thread.interrupted()) {
                throw new InterruptedException();
            }
            long stamp = tryRead(true);
            if (stamp != 0L) {
                return stamp;
            }
            return await(false, interruptible, timed, nanos);
        }

        private long await(boolean write, boolean interruptible, boolean timed, long nanos)
                throws InterruptedException {
            long deadline = timed ? System.nanoTime() + nanos : 0L;
            boolean restoreInterrupt = false;
            try {
                while (true) {
                    long stamp = write ? tryWrite() : tryRead(true);
                    if (stamp != 0L) {
                        return stamp;
                    }
                    long remaining = timed ? deadline - System.nanoTime() : 0L;
                    if (timed && remaining <= 0) {
                        return 0L;
                    }
                    try {
                        Monitors.await(this, timed, remaining);
                    } catch (InterruptedException e) {
                        Monitors.clearInterrupt();
                        if (interruptible) {
                            throw e;
                        }
                        restoreInterrupt = true;
                    }
                }
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            // A deserialized lock is unlocked, as in the JDK.
            long saved = version;
            if (isWritten(saved)) {
                version = saved + 1;
            }
        }
    }

    private final class ReadLockView implements TLock {

        @Override
        public void lock() {
            readLock();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }

        @Override
        public boolean tryLock() {
            return tryReadLock() != 0L;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }

        @Override
        public void unlock() {
            if (!tryUnlockRead()) {
                throw new IllegalMonitorStateException();
            }
        }

        @Override
        public TCondition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    private final class WriteLockView implements TLock {

        @Override
        public void lock() {
            writeLock();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }

        @Override
        public boolean tryLock() {
            return tryWriteLock() != 0L;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }

        @Override
        public void unlock() {
            if (!tryUnlockWrite()) {
                throw new IllegalMonitorStateException();
            }
        }

        @Override
        public TCondition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}
