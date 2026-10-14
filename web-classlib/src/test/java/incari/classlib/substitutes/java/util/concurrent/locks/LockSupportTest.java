package incari.classlib.substitutes.java.util.concurrent.locks;

import static incari.classlib.substitutes.java.util.concurrent.Concurrency.awaitBlocked;
import static incari.classlib.substitutes.java.util.concurrent.Concurrency.start;
import static org.assertj.core.api.Assertions.assertThat;

import incari.classlib.substitutes.java.util.concurrent.Concurrency.Worker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class LockSupportTest {

    @Test
    void unparkBeforeParkReturnsAtOnce() {
        TLockSupport.unpark(Thread.currentThread());
        TLockSupport.park();
        // The permit doesn't accumulate: two unparks give one.
        TLockSupport.unpark(Thread.currentThread());
        TLockSupport.unpark(Thread.currentThread());
        TLockSupport.park();
        long started = System.nanoTime();
        TLockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(30));
        assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(30));
    }

    @Test
    void parkWaitsForUnpark() throws InterruptedException {
        Object blocker = new Object();
        AtomicBoolean done = new AtomicBoolean();
        Worker parked = start(() -> {
            TLockSupport.park(blocker);
            done.set(true);
        });
        awaitBlocked(parked.thread());
        assertThat(TLockSupport.getBlocker(parked.thread())).isSameAs(blocker);
        assertThat(done).isFalse();
        TLockSupport.unpark(parked.thread());
        parked.join();
        assertThat(done).isTrue();
        assertThat(TLockSupport.getBlocker(parked.thread())).isNull();
    }

    @Test
    void interruptEndsParkAndStays() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean();
        Worker parked = start(() -> {
            TLockSupport.park();
            interrupted.set(Thread.currentThread().isInterrupted());
            // With the status still set, park returns at once.
            TLockSupport.park();
        });
        awaitBlocked(parked.thread());
        parked.thread().interrupt();
        parked.join();
        assertThat(interrupted).isTrue();
    }

    @Test
    void parkUntilAPastDeadlineReturns() {
        TLockSupport.parkUntil(System.currentTimeMillis() - 1);
        TLockSupport.parkUntil(new Object(), System.currentTimeMillis() + 10);
        TLockSupport.parkNanos(new Object(), 0);
        TLockSupport.unpark(null);
        TLockSupport.setCurrentBlocker(this);
        assertThat(TLockSupport.getBlocker(Thread.currentThread())).isSameAs(this);
        TLockSupport.setCurrentBlocker(null);
        assertThat(TLockSupport.getBlocker(Thread.currentThread())).isNull();
    }
}
