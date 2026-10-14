package incari.classlib.substitutes.java.util.concurrent.locks;

/** {@code java.util.concurrent.locks.ReadWriteLock}: a shared read lock and an exclusive write lock. */
public interface TReadWriteLock {

    TLock readLock();

    TLock writeLock();
}
