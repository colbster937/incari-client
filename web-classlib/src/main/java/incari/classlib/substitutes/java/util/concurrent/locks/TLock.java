package incari.classlib.substitutes.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * {@code java.util.concurrent.locks.Lock}. In the browser a thread that blocks here is a coroutine
 * that suspends until the lock is released, so the page's one real thread keeps running.
 */
public interface TLock {

    void lock();

    void lockInterruptibly() throws InterruptedException;

    boolean tryLock();

    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    void unlock();

    TCondition newCondition();
}
