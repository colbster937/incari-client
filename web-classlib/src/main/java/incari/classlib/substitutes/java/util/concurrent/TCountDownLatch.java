package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.concurrent.TimeUnit;

/**
 * {@code java.util.concurrent.CountDownLatch}: a count behind a private monitor; waiters
 * {@code wait} on it until {@link #countDown} brings it to zero.
 */
public class TCountDownLatch {

    private final Object lock = new Object();
    private long count;

    public TCountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    public void await() throws InterruptedException {
        synchronized (lock) {
            Monitors.checkInterrupt();
            while (count > 0) {
                lock.wait();
            }
        }
    }

    /** Waits up to the timeout for the count to reach zero; true if it did. */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (lock) {
            Monitors.checkInterrupt();
            while (count > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = Monitors.awaitNanos(lock, nanos);
            }
            return true;
        }
    }

    public void countDown() {
        synchronized (lock) {
            if (count > 0 && --count == 0) {
                lock.notifyAll();
            }
        }
    }

    public long getCount() {
        synchronized (lock) {
            return count;
        }
    }

    @Override
    public String toString() {
        return super.toString() + "[Count = " + getCount() + "]";
    }
}
