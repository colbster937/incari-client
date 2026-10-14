package incari.classlib.substitutes.java.util.concurrent.locks;

import incari.classlib.substitutes.java.util.concurrent.locks.TLockSupport.MonitorCondition;
import incari.classlib.substitutes.java.util.concurrent.locks.TLockSupport.Monitors;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.locks.ReentrantReadWriteLock}, built on a private monitor. Blocked
 * threads queue and {@code wait()} on it; in the browser they're coroutines suspended until a
 * release wakes them.
 *
 * <p>As in the JDK: both locks are reentrant; the writer may take the read lock too, so releasing
 * the write lock afterwards downgrades it; a reader can't upgrade (it waits for itself forever).
 * Each lock allows at most 65535 holds. A fair lock serves the queue in order; an unfair one lets a
 * newcomer barge in, except that a new reader waits behind a writer at the head of the queue so
 * writers aren't starved. Guava's cycle-detecting locks subclass this and both nested locks.
 */
// Threads (owners, waiters) are compared by identity, which is what Thread.equals is.
@SuppressWarnings("ReferenceEquality")
public class TReentrantReadWriteLock implements TReadWriteLock, Serializable {

    private static final long serialVersionUID = 1L;

    private static final int MAX_COUNT = 65535;

    private final ReadLock readerLock;
    private final WriteLock writerLock;
    final Sync sync;

    public TReentrantReadWriteLock() {
        this(false);
    }

    public TReentrantReadWriteLock(boolean fair) {
        sync = new Sync(fair);
        readerLock = new ReadLock(sync);
        writerLock = new WriteLock(sync);
    }

    @Override
    public ReadLock readLock() {
        return readerLock;
    }

    @Override
    public WriteLock writeLock() {
        return writerLock;
    }

    public final boolean isFair() {
        return sync.fair;
    }

    protected @Nullable Thread getOwner() {
        synchronized (sync) {
            return sync.writer;
        }
    }

    public int getReadLockCount() {
        synchronized (sync) {
            return sync.readCount;
        }
    }

    public boolean isWriteLocked() {
        synchronized (sync) {
            return sync.writer != null;
        }
    }

    public boolean isWriteLockedByCurrentThread() {
        synchronized (sync) {
            return sync.writer == Thread.currentThread();
        }
    }

    public int getWriteHoldCount() {
        synchronized (sync) {
            return sync.writer == Thread.currentThread() ? sync.writeHolds : 0;
        }
    }

    public int getReadHoldCount() {
        synchronized (sync) {
            return sync.readHolds(Thread.currentThread());
        }
    }

    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.queuedThreads(Sync.WRITERS);
    }

    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.queuedThreads(Sync.READERS);
    }

    public final boolean hasQueuedThreads() {
        synchronized (sync) {
            return !sync.queue.isEmpty();
        }
    }

    public final boolean hasQueuedThread(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        synchronized (sync) {
            for (Sync.Waiter waiter : sync.queue) {
                if (waiter.thread == thread) {
                    return true;
                }
            }
            return false;
        }
    }

    public final int getQueueLength() {
        synchronized (sync) {
            return sync.queue.size();
        }
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.queuedThreads(Sync.WRITERS | Sync.READERS);
    }

    public boolean hasWaiters(TCondition condition) {
        return ownCondition(condition).hasWaiters();
    }

    public int getWaitQueueLength(TCondition condition) {
        return ownCondition(condition).getWaitQueueLength();
    }

    protected Collection<Thread> getWaitingThreads(TCondition condition) {
        return ownCondition(condition).getWaitingThreads();
    }

    @Override
    public String toString() {
        int writes;
        int reads;
        synchronized (sync) {
            writes = sync.writeHolds;
            reads = sync.readCount;
        }
        return super.toString() + "[Write locks = " + writes + ", Read locks = " + reads + "]";
    }

    private MonitorCondition ownCondition(TCondition condition) {
        if (condition == null) {
            throw new NullPointerException();
        }
        if (!(condition instanceof MonitorCondition c) || c.owner() != sync) {
            throw new IllegalArgumentException("not owner");
        }
        return c;
    }

    /** The shared lock. {@link #newCondition} throws: only the write lock has conditions. */
    public static class ReadLock implements TLock, Serializable {

        private static final long serialVersionUID = 1L;

        private final Sync sync;

        protected ReadLock(TReentrantReadWriteLock lock) {
            this(lock.sync);
        }

        private ReadLock(Sync sync) {
            this.sync = sync;
        }

        @Override
        public void lock() {
            try {
                sync.acquire(false, false, false, 0L);
            } catch (InterruptedException e) {
                throw new AssertionError("uninterruptible lock was interrupted", e);
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            sync.acquire(false, true, false, 0L);
        }

        /** Takes the lock if no other thread writes, ignoring the queue even when fair (as the JDK). */
        @Override
        public boolean tryLock() {
            synchronized (sync) {
                return sync.tryAcquire(false);
            }
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.acquire(false, true, true, unit.toNanos(timeout));
        }

        @Override
        public void unlock() {
            sync.releaseRead();
        }

        @Override
        public TCondition newCondition() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            int reads;
            synchronized (sync) {
                reads = sync.readCount;
            }
            return super.toString() + "[Read locks = " + reads + "]";
        }
    }

    /** The exclusive lock, which also supports conditions. */
    public static class WriteLock implements TLock, Serializable {

        private static final long serialVersionUID = 1L;

        private final Sync sync;

        protected WriteLock(TReentrantReadWriteLock lock) {
            this(lock.sync);
        }

        private WriteLock(Sync sync) {
            this.sync = sync;
        }

        @Override
        public void lock() {
            try {
                sync.acquire(true, false, false, 0L);
            } catch (InterruptedException e) {
                throw new AssertionError("uninterruptible lock was interrupted", e);
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            sync.acquire(true, true, false, 0L);
        }

        /** Takes the lock if it's free right now, ignoring the queue even when fair (as the JDK). */
        @Override
        public boolean tryLock() {
            synchronized (sync) {
                return sync.tryAcquire(true);
            }
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.acquire(true, true, true, unit.toNanos(timeout));
        }

        @Override
        public void unlock() {
            sync.releaseWrite();
        }

        @Override
        public TCondition newCondition() {
            return new MonitorCondition(sync);
        }

        public boolean isHeldByCurrentThread() {
            synchronized (sync) {
                return sync.writer == Thread.currentThread();
            }
        }

        public int getHoldCount() {
            synchronized (sync) {
                return sync.writer == Thread.currentThread() ? sync.writeHolds : 0;
            }
        }

        @Override
        public String toString() {
            Thread owner;
            synchronized (sync) {
                owner = sync.writer;
            }
            return super.toString() + (owner == null ? "[Unlocked]" : "[Locked by thread " + owner.getName() + "]");
        }
    }

    /**
     * Both locks' state, guarded by this object's monitor. As a {@link TLockSupport.Exclusive} it's
     * the write lock, the side conditions belong to.
     */
    static final class Sync implements TLockSupport.Exclusive, Serializable {

        private static final long serialVersionUID = 1L;

        static final int WRITERS = 1;
        static final int READERS = 2;

        final boolean fair;
        transient @Nullable Thread writer;
        transient int writeHolds;

        /** Read holds per thread, and their total. */
        transient Map<Thread, Integer> readHoldsByThread = new HashMap<>();

        transient int readCount;

        /** Threads waiting in {@link #acquire}, oldest first. */
        transient List<Waiter> queue = new ArrayList<>();

        Sync(boolean fair) {
            this.fair = fair;
        }

        int readHolds(Thread thread) {
            Integer holds = readHoldsByThread.get(thread);
            return holds == null ? 0 : holds;
        }

        /** The queued threads of the given kinds ({@link #WRITERS} and/or {@link #READERS}). */
        Collection<Thread> queuedThreads(int kinds) {
            synchronized (this) {
                List<Thread> threads = new ArrayList<>();
                for (Waiter waiter : queue) {
                    if ((kinds & (waiter.writer ? WRITERS : READERS)) != 0) {
                        threads.add(waiter.thread);
                    }
                }
                return threads;
            }
        }

        /** Takes the lock now if it's free, barging past the queue ({@code tryLock()}). */
        boolean tryAcquire(boolean write) {
            return tryAcquire(write, false, null);
        }

        /**
         * Takes the lock now if the rules allow. {@code self} is the thread's queue entry, or null if
         * it hasn't queued; {@code respectQueue} applies the fairness and writer-preference rules.
         */
        private boolean tryAcquire(boolean write, boolean respectQueue, @Nullable Waiter self) {
            Thread thread = Thread.currentThread();
            if (write) {
                if (writer == thread) {
                    if (writeHolds == MAX_COUNT) {
                        throw new Error("Maximum lock count exceeded");
                    }
                    writeHolds++;
                    return true;
                }
                if (writer != null || readCount > 0 || (respectQueue && fair && !isFirst(self))) {
                    return false;
                }
                writer = thread;
                writeHolds = 1;
                return true;
            }
            if (writer != null && writer != thread) {
                return false;
            }
            int holds = readHolds(thread);
            // A thread that already reads (or writes) never queues behind others: it would deadlock.
            if (holds == 0 && writer == null && respectQueue && mustQueueNewReader(self)) {
                return false;
            }
            if (readCount == MAX_COUNT) {
                throw new Error("Maximum lock count exceeded");
            }
            readHoldsByThread.put(thread, holds + 1);
            readCount++;
            return true;
        }

        private boolean isFirst(@Nullable Waiter self) {
            return queue.isEmpty() || queue.get(0) == self;
        }

        private boolean mustQueueNewReader(@Nullable Waiter self) {
            if (queue.isEmpty() || queue.get(0) == self) {
                return false;
            }
            return fair || queue.get(0).writer;
        }

        /** Returns false only on timeout. */
        synchronized boolean acquire(boolean write, boolean interruptible, boolean timed, long nanos)
                throws InterruptedException {
            // Interruptible acquires check first, even for a free lock, as the JDK's do.
            if (interruptible && Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (tryAcquire(write, true, null)) {
                return true;
            }
            if (timed && nanos <= 0) {
                return false;
            }
            Thread thread = Thread.currentThread();
            long deadline = timed ? System.nanoTime() + nanos : 0L;
            boolean restoreInterrupt = false;
            Waiter self = new Waiter(thread, write);
            queue.add(self);
            try {
                while (true) {
                    if (tryAcquire(write, true, self)) {
                        return true;
                    }
                    long remaining = timed ? deadline - System.nanoTime() : 0L;
                    if (timed && remaining <= 0) {
                        return false;
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
                queue.remove(self);
                // Whoever is behind this thread may be able to go now, whether it got the lock
                // (a reader lets the readers after it in) or gave up.
                notifyAll();
                if (restoreInterrupt) {
                    thread.interrupt();
                }
            }
        }

        synchronized void releaseRead() {
            Thread thread = Thread.currentThread();
            int holds = readHolds(thread);
            if (holds == 0) {
                throw new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread");
            }
            if (holds == 1) {
                readHoldsByThread.remove(thread);
            } else {
                readHoldsByThread.put(thread, holds - 1);
            }
            if (--readCount == 0) {
                notifyAll();
            }
        }

        synchronized void releaseWrite() {
            if (writer != Thread.currentThread()) {
                throw new IllegalMonitorStateException();
            }
            if (--writeHolds == 0) {
                writer = null;
                notifyAll();
            }
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            readHoldsByThread = new HashMap<>();
            queue = new ArrayList<>();
        }

        // The write lock's hooks for MonitorCondition; all run with the monitor held.

        @Override
        public Object monitor() {
            return this;
        }

        @Override
        public boolean isHeldExclusively() {
            return writer == Thread.currentThread();
        }

        @Override
        public int releaseAll() {
            int released = writeHolds;
            writeHolds = 0;
            writer = null;
            notifyAll();
            return released;
        }

        @Override
        public boolean reacquire(int holds) {
            boolean interrupted = false;
            while (writer != null || readCount > 0) {
                try {
                    Monitors.await(this, false, 0L);
                } catch (InterruptedException e) {
                    Monitors.clearInterrupt();
                    interrupted = true;
                }
            }
            writer = Thread.currentThread();
            writeHolds = holds;
            return interrupted;
        }

        static final class Waiter {
            final Thread thread;
            final boolean writer;

            Waiter(Thread thread, boolean writer) {
                this.thread = thread;
                this.writer = writer;
            }
        }
    }
}
