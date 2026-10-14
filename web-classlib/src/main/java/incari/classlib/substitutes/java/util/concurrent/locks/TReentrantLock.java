package incari.classlib.substitutes.java.util.concurrent.locks;

import incari.classlib.substitutes.java.util.concurrent.locks.TLockSupport.MonitorCondition;
import incari.classlib.substitutes.java.util.concurrent.locks.TLockSupport.Monitors;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.locks.ReentrantLock}, built on a private monitor instead of an
 * {@code AbstractQueuedSynchronizer}. A thread that can't take the lock queues and {@code wait()}s
 * on the monitor; in the browser that suspends the coroutine until an unlock wakes it.
 *
 * <p>Not final and every method overridable where the JDK's is: Guava's cache segments extend it.
 * A fair lock hands the lock to the longest-waiting thread; an unfair one lets a newcomer take a
 * free lock ahead of the queue. A deserialized lock is unlocked, as in the JDK.
 */
// Threads (owners, waiters) are compared by identity, which is what Thread.equals is.
@SuppressWarnings("ReferenceEquality")
public class TReentrantLock implements TLock, Serializable {

    private static final long serialVersionUID = 1L;

    private final Sync sync;

    public TReentrantLock() {
        this(false);
    }

    public TReentrantLock(boolean fair) {
        sync = new Sync(fair);
    }

    @Override
    public void lock() {
        try {
            sync.acquire(false, false, 0L);
        } catch (InterruptedException e) {
            throw new AssertionError("uninterruptible lock was interrupted", e);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        sync.acquire(true, false, 0L);
    }

    /** Takes the lock if it's free right now, even on a fair lock with waiting threads (as the JDK). */
    @Override
    public boolean tryLock() {
        return sync.tryAcquire(false);
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.acquire(true, true, unit.toNanos(timeout));
    }

    @Override
    public void unlock() {
        sync.release();
    }

    @Override
    public TCondition newCondition() {
        return new MonitorCondition(sync);
    }

    public int getHoldCount() {
        synchronized (sync) {
            return sync.owner == Thread.currentThread() ? sync.holds : 0;
        }
    }

    public boolean isHeldByCurrentThread() {
        synchronized (sync) {
            return sync.owner == Thread.currentThread();
        }
    }

    public boolean isLocked() {
        synchronized (sync) {
            return sync.owner != null;
        }
    }

    public final boolean isFair() {
        return sync.fair;
    }

    protected @Nullable Thread getOwner() {
        synchronized (sync) {
            return sync.owner;
        }
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
            return sync.queue.contains(thread);
        }
    }

    public final int getQueueLength() {
        synchronized (sync) {
            return sync.queue.size();
        }
    }

    protected Collection<Thread> getQueuedThreads() {
        synchronized (sync) {
            return new ArrayList<>(sync.queue);
        }
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
        Thread owner = getOwner();
        return super.toString() + (owner == null ? "[Unlocked]" : "[Locked by thread " + owner.getName() + "]");
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

    /** The lock's state, guarded by its own monitor (private, so no caller can hold it by accident). */
    private static final class Sync implements TLockSupport.Exclusive, Serializable {

        private static final long serialVersionUID = 1L;

        final boolean fair;
        transient @Nullable Thread owner;
        transient int holds;

        /** Threads waiting in {@link #acquire}, oldest first. */
        transient List<Thread> queue = new ArrayList<>();

        Sync(boolean fair) {
            this.fair = fair;
        }

        synchronized boolean tryAcquire(boolean respectQueue) {
            Thread thread = Thread.currentThread();
            if (owner == thread) {
                addHold();
                return true;
            }
            if (owner == null && (!respectQueue || queue.isEmpty())) {
                owner = thread;
                holds = 1;
                return true;
            }
            return false;
        }

        /**
         * Takes the lock, waiting for it if needed. Returns false only on timeout. An uninterruptible
         * acquire that gets interrupted carries on and sets the interrupt status again at the end.
         */
        synchronized boolean acquire(boolean interruptible, boolean timed, long nanos) throws InterruptedException {
            // Interruptible acquires check first, even for a free lock, as the JDK's do.
            if (interruptible && Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (tryAcquire(fair)) {
                return true;
            }
            if (timed && nanos <= 0) {
                return false;
            }
            Thread thread = Thread.currentThread();
            long deadline = timed ? System.nanoTime() + nanos : 0L;
            boolean restoreInterrupt = false;
            queue.add(thread);
            try {
                while (true) {
                    if (owner == null && (!fair || queue.get(0) == thread)) {
                        queue.remove(thread);
                        owner = thread;
                        holds = 1;
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
                // Leaving the queue without the lock can make someone else the head of a fair queue.
                if (queue.remove(thread)) {
                    notifyAll();
                }
                if (restoreInterrupt) {
                    thread.interrupt();
                }
            }
        }

        synchronized void release() {
            if (owner != Thread.currentThread()) {
                throw new IllegalMonitorStateException();
            }
            if (--holds == 0) {
                owner = null;
                notifyAll();
            }
        }

        private void addHold() {
            if (holds == Integer.MAX_VALUE) {
                throw new Error("Maximum lock count exceeded");
            }
            holds++;
        }

        @Override
        public Object monitor() {
            return this;
        }

        @Override
        public boolean isHeldExclusively() {
            return owner == Thread.currentThread();
        }

        @Override
        public int releaseAll() {
            int released = holds;
            holds = 0;
            owner = null;
            notifyAll();
            return released;
        }

        @Override
        public boolean reacquire(int savedHolds) {
            Thread thread = Thread.currentThread();
            boolean interrupted = false;
            while (owner != null) {
                try {
                    Monitors.await(this, false, 0L);
                } catch (InterruptedException e) {
                    Monitors.clearInterrupt();
                    interrupted = true;
                }
            }
            owner = thread;
            holds = savedHolds;
            return interrupted;
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            queue = new ArrayList<>();
        }
    }
}
