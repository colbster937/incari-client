package incari.classlib.concurrent;

import java.util.concurrent.locks.Lock;

/**
 * Where the browser build's non-waiting lock acquisitions go. The web build's
 * {@code MonitorElision} routes {@code lock()} calls here in methods that never suspend otherwise:
 * in the page such a method runs exclusively, so the lock is free, or already the current
 * thread's, whenever it's reached, and taking it needn't wait. A lock held by another thread here
 * means that thread suspended while holding it, which the analysis rules out; failing loudly is
 * better than proceeding without it. The web build refers to this class by name.
 */
public final class Locks {

    private Locks() {}

    public static void lockNow(Lock lock) {
        if (!lock.tryLock()) {
            throw new IllegalStateException(
                    "Lock held by a suspended thread; the browser build can't wait for it here");
        }
    }
}
