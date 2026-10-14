package incari.classlib.substitutes.java.util.concurrent.locks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.locks.LockSupport}: a one-shot permit per thread. {@link #unpark}
 * makes the permit available, {@link #park} consumes it or waits for it.
 *
 * <p>There's no native parking in the browser, so the permits live in a {@link WeakHashMap} keyed
 * by thread (a finished thread's entry goes with it), guarded by one package-wide monitor. A parked
 * thread {@code wait()}s on that monitor and {@code unpark} wakes all of them to recheck their
 * permit: in the browser those are coroutines resuming, cheap for the handful that ever park, and
 * nothing spins.
 *
 * <p>Also holds the monitor-wait helpers the locks in this package share (nested classes, since a
 * top-level {@code T} class here is taken for a substitute).
 */
public final class TLockSupport {

    private static final Object MONITOR = new Object();

    /** Threads holding a permit. The value is unused; presence is the permit. */
    private static final Map<Thread, Boolean> PERMITS = new WeakHashMap<>();

    private static final Map<Thread, Object> BLOCKERS = new WeakHashMap<>();

    private TLockSupport() {}

    public static void unpark(@Nullable Thread thread) {
        if (thread == null) {
            return;
        }
        synchronized (MONITOR) {
            PERMITS.put(thread, Boolean.TRUE);
            MONITOR.notifyAll();
        }
    }

    public static void park() {
        park(null, false, 0L, false);
    }

    public static void park(@Nullable Object blocker) {
        park(blocker, false, 0L, true);
    }

    public static void parkNanos(long nanos) {
        if (nanos > 0) {
            park(null, true, nanos, false);
        }
    }

    public static void parkNanos(@Nullable Object blocker, long nanos) {
        if (nanos > 0) {
            park(blocker, true, nanos, true);
        }
    }

    /** Parks until {@code deadline}, in milliseconds since the epoch. */
    public static void parkUntil(long deadline) {
        parkNanos(untilNanos(deadline));
    }

    public static void parkUntil(@Nullable Object blocker, long deadline) {
        parkNanos(blocker, untilNanos(deadline));
    }

    public static @Nullable Object getBlocker(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        synchronized (MONITOR) {
            return BLOCKERS.get(thread);
        }
    }

    public static void setCurrentBlocker(@Nullable Object blocker) {
        setBlocker(Thread.currentThread(), blocker);
    }

    private static void setBlocker(Thread thread, @Nullable Object blocker) {
        synchronized (MONITOR) {
            if (blocker == null) {
                BLOCKERS.remove(thread);
            } else {
                BLOCKERS.put(thread, blocker);
            }
        }
    }

    private static long untilNanos(long deadlineMillis) {
        return TimeUnit.MILLISECONDS.toNanos(deadlineMillis - System.currentTimeMillis());
    }

    /**
     * Waits for the permit, an interrupt, or the timeout. Leaves the interrupt status set, as the
     * JDK's park does, so callers can see why it returned.
     */
    private static void park(@Nullable Object blocker, boolean timed, long nanos, boolean withBlocker) {
        Thread thread = Thread.currentThread();
        long deadline = timed ? System.nanoTime() + nanos : 0L;
        synchronized (MONITOR) {
            if (PERMITS.remove(thread) != null || thread.isInterrupted()) {
                return;
            }
            if (withBlocker) {
                setBlocker(thread, blocker);
            }
            try {
                while (PERMITS.remove(thread) == null) {
                    long remaining = timed ? deadline - System.nanoTime() : 0L;
                    if (timed && remaining <= 0) {
                        return;
                    }
                    try {
                        Monitors.await(MONITOR, timed, remaining);
                    } catch (InterruptedException e) {
                        thread.interrupt();
                        return;
                    }
                }
            } finally {
                if (withBlocker) {
                    setBlocker(thread, null);
                }
            }
        }
    }

    /** Timed waits on a monitor, for the locks in this package. */
    static final class Monitors {

        private Monitors() {}

        /**
         * One {@code wait} on {@code monitor}, which the caller holds, for at most {@code nanos} if
         * timed. TeaVM only has {@code wait()} and {@code wait(long)}, so the time rounds up to whole
         * milliseconds (never 0, which would mean forever).
         */
        @SuppressWarnings("WaitNotInLoop") // every caller loops on its own condition
        static void await(Object monitor, boolean timed, long nanos) throws InterruptedException {
            if (!timed) {
                monitor.wait();
            } else {
                long millis = nanos / 1_000_000L;
                if (millis * 1_000_000L < nanos) {
                    millis++;
                }
                monitor.wait(Math.max(1L, millis));
            }
        }

        /**
         * Clears the interrupt status after an {@link InterruptedException}. The JVM already has, but
         * TeaVM's {@code wait} may leave it set, and a set flag must not make the next wait return
         * at once (that would spin).
         */
        static void clearInterrupt() {
            Thread.interrupted();
        }
    }

    /** What a {@link MonitorCondition} needs from its lock. Called with {@link #monitor()} held. */
    interface Exclusive {

        Object monitor();

        boolean isHeldExclusively();

        /** Releases every hold the current thread has and returns how many there were. */
        int releaseAll();

        /**
         * Takes the lock back with {@code holds} holds, waiting on the monitor as long as needed and
         * ignoring interrupts. Returns whether the thread was interrupted meanwhile.
         */
        boolean reacquire(int holds);
    }

    /**
     * A {@link TCondition} for a lock whose state is guarded by one monitor. Waiters queue in
     * order; {@code signal} marks the oldest and wakes the monitor.
     */
    static final class MonitorCondition implements TCondition {

        private final Exclusive lock;
        private final List<Waiter> waiters = new ArrayList<>();

        MonitorCondition(Exclusive lock) {
            this.lock = lock;
        }

        Exclusive owner() {
            return lock;
        }

        @Override
        public void await() throws InterruptedException {
            await(true, false, 0L);
        }

        @Override
        public void awaitUninterruptibly() {
            try {
                await(false, false, 0L);
            } catch (InterruptedException e) {
                throw new AssertionError("uninterruptible wait was interrupted", e);
            }
        }

        @Override
        public long awaitNanos(long nanosTimeout) throws InterruptedException {
            long deadline = System.nanoTime() + nanosTimeout;
            await(true, true, nanosTimeout);
            return deadline - System.nanoTime();
        }

        @Override
        public boolean await(long time, TimeUnit unit) throws InterruptedException {
            return await(true, true, unit.toNanos(time));
        }

        @Override
        @SuppressWarnings("JavaUtilDate") // the Condition API takes a Date
        public boolean awaitUntil(Date deadline) throws InterruptedException {
            return await(true, true, untilNanos(deadline.getTime()));
        }

        @Override
        public void signal() {
            synchronized (lock.monitor()) {
                checkHeld();
                if (!waiters.isEmpty()) {
                    waiters.remove(0).signalled = true;
                    lock.monitor().notifyAll();
                }
            }
        }

        @Override
        public void signalAll() {
            synchronized (lock.monitor()) {
                checkHeld();
                if (!waiters.isEmpty()) {
                    for (Waiter waiter : waiters) {
                        waiter.signalled = true;
                    }
                    waiters.clear();
                    lock.monitor().notifyAll();
                }
            }
        }

        boolean hasWaiters() {
            synchronized (lock.monitor()) {
                checkHeld();
                return !waiters.isEmpty();
            }
        }

        int getWaitQueueLength() {
            synchronized (lock.monitor()) {
                checkHeld();
                return waiters.size();
            }
        }

        Collection<Thread> getWaitingThreads() {
            synchronized (lock.monitor()) {
                checkHeld();
                List<Thread> threads = new ArrayList<>(waiters.size());
                for (Waiter waiter : waiters) {
                    threads.add(waiter.thread);
                }
                return threads;
            }
        }

        private void checkHeld() {
            if (!lock.isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
        }

        /** Returns whether the thread was signalled (rather than timed out). */
        private boolean await(boolean interruptible, boolean timed, long nanos) throws InterruptedException {
            Object monitor = lock.monitor();
            synchronized (monitor) {
                checkHeld();
                if (interruptible && Thread.interrupted()) {
                    throw new InterruptedException();
                }
                long deadline = timed ? System.nanoTime() + nanos : 0L;
                Waiter waiter = new Waiter(Thread.currentThread());
                waiters.add(waiter);
                int holds = lock.releaseAll();
                boolean throwInterrupt = false;
                boolean restoreInterrupt = false;
                try {
                    while (!waiter.signalled) {
                        long remaining = timed ? deadline - System.nanoTime() : 0L;
                        if (timed && remaining <= 0) {
                            break;
                        }
                        try {
                            Monitors.await(monitor, timed, remaining);
                        } catch (InterruptedException e) {
                            Monitors.clearInterrupt();
                            // Interrupted after a signal: the signal wins and the interrupt is kept
                            // for later, so the signal isn't lost (the Condition contract).
                            if (interruptible && !waiter.signalled) {
                                throwInterrupt = true;
                                break;
                            }
                            restoreInterrupt = true;
                        }
                    }
                } finally {
                    if (!waiter.signalled) {
                        waiters.remove(waiter);
                    }
                    if (lock.reacquire(holds)) {
                        restoreInterrupt = true;
                    }
                }
                if (throwInterrupt) {
                    throw new InterruptedException();
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
                return waiter.signalled;
            }
        }

        private static final class Waiter {
            final Thread thread;
            boolean signalled;

            Waiter(Thread thread) {
                this.thread = thread;
            }
        }
    }
}
