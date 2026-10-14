package incari.classlib.substitutes.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * {@code java.util.concurrent.locks.Condition}. The implementations in this package suspend the
 * waiting coroutine on their lock's monitor, so a browser tab never spins while a thread waits.
 */
public interface TCondition {

    void await() throws InterruptedException;

    void awaitUninterruptibly();

    long awaitNanos(long nanosTimeout) throws InterruptedException;

    boolean await(long time, TimeUnit unit) throws InterruptedException;

    boolean awaitUntil(Date deadline) throws InterruptedException;

    void signal();

    void signalAll();
}
