package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.CyclicBarrier}. Each trip is a new {@link Round}; the parties of a
 * round {@code wait} on a private monitor until the last one arrives (and runs the barrier action)
 * or the round breaks through an interrupt, a timeout, a failing action or {@link #reset}.
 */
public class TCyclicBarrier {

    private final Object lock = new Object();
    private final int parties;
    private final @Nullable Runnable barrierAction;

    // Guarded by lock.
    private Round round = new Round();
    private int waiting;

    /** One use of the barrier. */
    private static final class Round {
        boolean broken;
    }

    public TCyclicBarrier(int parties, @Nullable Runnable barrierAction) {
        if (parties <= 0) {
            throw new IllegalArgumentException();
        }
        this.parties = parties;
        this.barrierAction = barrierAction;
    }

    public TCyclicBarrier(int parties) {
        this(parties, null);
    }

    public int getParties() {
        return parties;
    }

    /** Waits for all parties; returns this party's arrival index (parties - 1 first, 0 last). */
    public int await() throws InterruptedException, TBrokenBarrierException {
        try {
            return await(false, 0L);
        } catch (TTimeoutException e) {
            throw new AssertionError("an untimed await can't time out", e);
        }
    }

    public int await(long timeout, TimeUnit unit)
            throws InterruptedException, TBrokenBarrierException, TTimeoutException {
        return await(true, unit.toNanos(timeout));
    }

    private int await(boolean timed, long nanos)
            throws InterruptedException, TBrokenBarrierException, TTimeoutException {
        synchronized (lock) {
            Round current = round;
            if (current.broken) {
                throw new TBrokenBarrierException();
            }
            if (Thread.interrupted()) {
                breakRound();
                throw new InterruptedException();
            }
            int index = parties - 1 - waiting;
            if (index == 0) {
                trip();
                return 0;
            }
            waiting++;
            while (true) {
                try {
                    if (!timed) {
                        lock.wait();
                    } else if (nanos > 0) {
                        nanos = Monitors.awaitNanos(lock, nanos);
                    }
                } catch (InterruptedException e) {
                    if (current == round && !current.broken) {
                        breakRound();
                        throw e;
                    }
                    // The round already ended: the interrupt belongs to whatever comes next.
                    Thread.currentThread().interrupt();
                }
                if (current.broken) {
                    throw new TBrokenBarrierException();
                }
                if (current != round) {
                    return index;
                }
                if (timed && nanos <= 0) {
                    breakRound();
                    throw new TTimeoutException();
                }
            }
        }
    }

    // Caller holds lock: the last party has arrived.
    private void trip() {
        Runnable action = barrierAction;
        boolean ran = false;
        try {
            if (action != null) {
                action.run();
            }
            ran = true;
        } finally {
            if (ran) {
                round = new Round();
                waiting = 0;
                lock.notifyAll();
            } else {
                breakRound();
            }
        }
    }

    // Caller holds lock.
    private void breakRound() {
        round.broken = true;
        waiting = 0;
        lock.notifyAll();
    }

    public boolean isBroken() {
        synchronized (lock) {
            return round.broken;
        }
    }

    /** Breaks the current round (its waiters get BrokenBarrierException) and starts a fresh one. */
    public void reset() {
        synchronized (lock) {
            breakRound();
            round = new Round();
        }
    }

    public int getNumberWaiting() {
        synchronized (lock) {
            return waiting;
        }
    }
}
