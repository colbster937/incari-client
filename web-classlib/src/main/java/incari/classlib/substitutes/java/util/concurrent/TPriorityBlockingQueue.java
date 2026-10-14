package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.PriorityBlockingQueue}: an unbounded priority queue whose takers
 * block while it's empty. A {@link PriorityQueue} behind the queue's monitor; iterators work on a
 * snapshot, in no particular order.
 */
public class TPriorityBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private static final int DEFAULT_INITIAL_CAPACITY = 11;

    private final PriorityQueue<E> queue;
    private int waiters;

    public TPriorityBlockingQueue() {
        this(DEFAULT_INITIAL_CAPACITY, null);
    }

    public TPriorityBlockingQueue(int initialCapacity) {
        this(initialCapacity, null);
    }

    public TPriorityBlockingQueue(int initialCapacity, @Nullable Comparator<? super E> comparator) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException();
        }
        queue = new PriorityQueue<>(initialCapacity, comparator);
    }

    public TPriorityBlockingQueue(Collection<? extends E> c) {
        queue = new PriorityQueue<>(c);
    }

    @Override
    public boolean add(E e) {
        return offer(e);
    }

    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        synchronized (this) {
            queue.offer(e);
            if (waiters > 0) {
                notifyAll();
            }
            return true;
        }
    }

    /** Never blocks: the queue is unbounded. */
    @Override
    public void put(E e) {
        offer(e);
    }

    /** Never blocks: the queue is unbounded. */
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e);
    }

    @Override
    public synchronized @Nullable E poll() {
        return queue.poll();
    }

    @Override
    public synchronized E take() throws InterruptedException {
        Monitors.checkInterrupt();
        E e;
        while ((e = queue.poll()) == null) {
            waiters++;
            try {
                wait();
            } finally {
                waiters--;
            }
        }
        return e;
    }

    @Override
    public synchronized @Nullable E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Monitors.checkInterrupt();
        long nanos = unit.toNanos(timeout);
        E e;
        while ((e = queue.poll()) == null) {
            if (nanos <= 0) {
                return null;
            }
            waiters++;
            try {
                nanos = Monitors.awaitNanos(this, nanos);
            } finally {
                waiters--;
            }
        }
        return e;
    }

    @Override
    public synchronized @Nullable E peek() {
        return queue.peek();
    }

    public @Nullable Comparator<? super E> comparator() {
        return queue.comparator();
    }

    @Override
    public synchronized int size() {
        return queue.size();
    }

    @Override
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public synchronized boolean remove(@Nullable Object o) {
        return o != null && queue.remove(o);
    }

    @Override
    public synchronized boolean contains(@Nullable Object o) {
        return o != null && queue.contains(o);
    }

    @Override
    public synchronized String toString() {
        return queue.toString();
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
            E e;
            while (n < maxElements && (e = queue.poll()) != null) {
                c.add(e);
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void clear() {
        queue.clear();
    }

    @Override
    public synchronized Object[] toArray() {
        return queue.toArray();
    }

    @Override
    public synchronized <T> T[] toArray(T[] a) {
        return queue.toArray(a);
    }

    @Override
    public Iterator<E> iterator() {
        return new SnapshotIterator<>(toArray(), this::removeExact);
    }

    private synchronized void removeExact(Object o) {
        SnapshotIterator.removeExact(queue.iterator(), o);
    }
}
