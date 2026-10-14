package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.LinkedBlockingQueue}: an optionally bounded FIFO blocking queue.
 *
 * <p>One monitor (the queue itself) guards an {@link ArrayDeque}, where the JDK uses two locks
 * over linked nodes: the browser runs one coroutine at a time, so there's no contention to split.
 * Blocked producers and consumers {@code wait} on it and are only notified while someone waits,
 * which keeps the uncontended {@code offer}/{@code poll} path cheap. Iterators work on a snapshot.
 */
public class TLinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final int capacity;
    private final ArrayDeque<E> items = new ArrayDeque<>();
    private int waiters;

    public TLinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    public TLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
    }

    public TLinkedBlockingQueue(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        for (E e : c) {
            items.addLast(Objects.requireNonNull(e));
        }
    }

    // Caller holds this monitor. nanos < 0 waits until notified; returns the time left.
    @SuppressWarnings("WaitNotInLoop") // every caller loops on its own condition
    private long await(long nanos) throws InterruptedException {
        waiters++;
        try {
            if (nanos < 0) {
                wait();
                return -1L;
            }
            return Monitors.awaitNanos(this, nanos);
        } finally {
            waiters--;
        }
    }

    // Caller holds this monitor.
    private void signal() {
        if (waiters > 0) {
            notifyAll();
        }
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized int remainingCapacity() {
        return capacity - items.size();
    }

    @Override
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        synchronized (this) {
            Monitors.checkInterrupt();
            while (items.size() >= capacity) {
                await(-1L);
            }
            items.addLast(e);
            signal();
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        synchronized (this) {
            Monitors.checkInterrupt();
            while (items.size() >= capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = await(nanos);
            }
            items.addLast(e);
            signal();
            return true;
        }
    }

    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        synchronized (this) {
            if (items.size() >= capacity) {
                return false;
            }
            items.addLast(e);
            signal();
            return true;
        }
    }

    @Override
    public synchronized E take() throws InterruptedException {
        Monitors.checkInterrupt();
        while (items.isEmpty()) {
            await(-1L);
        }
        E e = items.removeFirst();
        signal();
        return e;
    }

    @Override
    public synchronized @Nullable E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Monitors.checkInterrupt();
        long nanos = unit.toNanos(timeout);
        while (items.isEmpty()) {
            if (nanos <= 0) {
                return null;
            }
            nanos = await(nanos);
        }
        E e = items.removeFirst();
        signal();
        return e;
    }

    @Override
    public synchronized @Nullable E poll() {
        E e = items.pollFirst();
        if (e != null) {
            signal();
        }
        return e;
    }

    @Override
    public synchronized @Nullable E peek() {
        return items.peekFirst();
    }

    @Override
    public synchronized boolean remove(@Nullable Object o) {
        if (o != null && items.remove(o)) {
            signal();
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean contains(@Nullable Object o) {
        return o != null && items.contains(o);
    }

    @Override
    public synchronized Object[] toArray() {
        return items.toArray();
    }

    @Override
    public synchronized <T> T[] toArray(T[] a) {
        return items.toArray(a);
    }

    @Override
    public synchronized String toString() {
        return items.toString();
    }

    @Override
    public synchronized void clear() {
        items.clear();
        signal();
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @SuppressWarnings("ReferenceEquality") // the contract is about this very collection
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this) {
            throw new IllegalArgumentException();
        }
        int n = 0;
        synchronized (this) {
            while (n < maxElements && !items.isEmpty()) {
                c.add(items.removeFirst());
                n++;
            }
            if (n > 0) {
                signal();
            }
        }
        return n;
    }

    @Override
    public Iterator<E> iterator() {
        return new SnapshotIterator<>(toArray(), this::removeExact);
    }

    private synchronized void removeExact(Object o) {
        SnapshotIterator.removeExact(items.iterator(), o);
        signal();
    }
}
