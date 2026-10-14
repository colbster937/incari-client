package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * {@code java.util.concurrent.Semaphore}: a permit count behind a private monitor. Blocked
 * acquirers queue up in arrival order and {@code wait}. A fair semaphore serves them strictly in
 * that order; a non-fair one lets any acquirer take permits as soon as enough are free. Untimed
 * {@link #tryAcquire()} barges in both modes, as the JDK documents.
 */
public class TSemaphore {

    private final Object lock = new Object();
    private final boolean fair;

    // Guarded by lock.
    private int permits;
    private final ArrayDeque<Waiter> queue = new ArrayDeque<>();

    /** A blocked acquirer. Compared by identity. */
    private static final class Waiter {
        final Thread thread = Thread.currentThread();
    }

    public TSemaphore(int permits) {
        this(permits, false);
    }

    public TSemaphore(int permits, boolean fair) {
        this.permits = permits;
        this.fair = fair;
    }

    // Caller holds lock. Whether `waiter` (null for a newcomer) may take n permits now.
    private boolean mayTake(int n, Waiter waiter) {
        if (permits < n) {
            return false;
        }
        return !fair || queue.peekFirst() == waiter;
    }

    // Caller holds lock. A newcomer may take n permits without queueing.
    private boolean mayTakeNow(int n) {
        return permits >= n && (!fair || queue.isEmpty());
    }

    /**
     * Takes {@code n} permits, waiting (forever if not {@code timed}, else up to {@code nanos}).
     * Returns false on timeout; an interrupt is thrown if {@code interruptible}, else re-asserted.
     */
    private boolean acquire(int n, boolean interruptible, boolean timed, long nanos) throws InterruptedException {
        if (n < 0) {
            throw new IllegalArgumentException();
        }
        boolean interrupted = false;
        synchronized (lock) {
            if (interruptible) {
                Monitors.checkInterrupt();
            }
            if (mayTakeNow(n)) {
                permits -= n;
                return true;
            }
            if (timed && nanos <= 0) {
                return false;
            }
            Waiter me = new Waiter();
            queue.addLast(me);
            try {
                while (!mayTake(n, me)) {
                    try {
                        if (!timed) {
                            lock.wait();
                        } else if (nanos > 0) {
                            nanos = Monitors.awaitNanos(lock, nanos);
                        } else {
                            return false;
                        }
                    } catch (InterruptedException e) {
                        if (interruptible) {
                            throw e;
                        }
                        interrupted = true;
                    }
                }
                permits -= n;
                return true;
            } finally {
                queue.remove(me);
                // The head may have changed, or permits remain for someone else.
                lock.notifyAll();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void acquire() throws InterruptedException {
        acquire(1, true, false, 0L);
    }

    public void acquire(int permits) throws InterruptedException {
        acquire(permits, true, false, 0L);
    }

    public void acquireUninterruptibly() {
        acquireUninterruptibly(1);
    }

    public void acquireUninterruptibly(int permits) {
        try {
            acquire(permits, false, false, 0L);
        } catch (InterruptedException e) {
            throw new AssertionError("an uninterruptible acquire doesn't throw", e);
        }
    }

    /** Takes a permit if one is free, even ahead of queued acquirers. */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /** Takes {@code permits} if that many are free, even ahead of queued acquirers. */
    public boolean tryAcquire(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (lock) {
            if (this.permits >= permits) {
                this.permits -= permits;
                return true;
            }
            return false;
        }
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return acquire(1, true, true, unit.toNanos(timeout));
    }

    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        return acquire(permits, true, true, unit.toNanos(timeout));
    }

    public void release() {
        release(1);
    }

    public void release(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (lock) {
            if (this.permits + permits < this.permits) {
                throw new Error("Maximum permit count exceeded");
            }
            this.permits += permits;
            if (!queue.isEmpty()) {
                lock.notifyAll();
            }
        }
    }

    public int availablePermits() {
        synchronized (lock) {
            return permits;
        }
    }

    /** Takes all free permits and returns how many that was (a negative count is reset to 0). */
    public int drainPermits() {
        synchronized (lock) {
            int drained = permits;
            permits = 0;
            return drained;
        }
    }

    /** Removes permits without waiting, possibly leaving the count negative. */
    protected void reducePermits(int reduction) {
        if (reduction < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (lock) {
            if (permits - reduction > permits) {
                throw new Error("Permit count underflow");
            }
            permits -= reduction;
        }
    }

    public boolean isFair() {
        return fair;
    }

    public final boolean hasQueuedThreads() {
        synchronized (lock) {
            return !queue.isEmpty();
        }
    }

    public final int getQueueLength() {
        synchronized (lock) {
            return queue.size();
        }
    }

    protected Collection<Thread> getQueuedThreads() {
        synchronized (lock) {
            Collection<Thread> threads = new ArrayList<>(queue.size());
            for (Waiter w : queue) {
                threads.add(w.thread);
            }
            return threads;
        }
    }

    @Override
    public String toString() {
        return super.toString() + "[Permits = " + availablePermits() + "]";
    }
}
