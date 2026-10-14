package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.DelayQueue}: an unbounded queue whose elements can only be taken once
 * their delay has run out. A priority queue behind the queue's monitor; {@link #take} waits for the
 * head's remaining delay, and adding an element wakes the waiters so they can re-check the head.
 *
 * <p>Iterators work on a snapshot; their {@code remove} removes that element from the queue.
 */
public class TDelayQueue<E extends TDelayed> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final PriorityQueue<E> queue = new PriorityQueue<>();
    private int waiters;

    public TDelayQueue() {}

    public TDelayQueue(Collection<? extends E> c) {
        for (E e : c) {
            queue.add(Objects.requireNonNull(e));
        }
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
            signal();
            return true;
        }
    }

    @Override
    public void put(E e) {
        offer(e);
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e);
    }

    @Override
    public synchronized @Nullable E poll() {
        E head = queue.peek();
        if (head == null || head.getDelay(TimeUnit.NANOSECONDS) > 0) {
            return null;
        }
        return queue.poll();
    }

    @Override
    public synchronized E take() throws InterruptedException {
        Monitors.checkInterrupt();
        while (true) {
            E head = queue.peek();
            if (head == null) {
                await(-1L);
            } else {
                long delay = head.getDelay(TimeUnit.NANOSECONDS);
                if (delay <= 0) {
                    return Objects.requireNonNull(queue.poll());
                }
                await(delay);
            }
        }
    }

    @Override
    public synchronized @Nullable E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Monitors.checkInterrupt();
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        while (true) {
            E head = queue.peek();
            long wait = deadline - System.nanoTime();
            if (head != null) {
                long delay = head.getDelay(TimeUnit.NANOSECONDS);
                if (delay <= 0) {
                    return queue.poll();
                }
                if (wait <= 0) {
                    return null;
                }
                wait = Math.min(wait, delay);
            } else if (wait <= 0) {
                return null;
            }
            await(wait);
        }
    }

    // Caller holds this monitor. nanos < 0 waits until notified.
    @SuppressWarnings("WaitNotInLoop") // every caller loops on its own condition
    private void await(long nanos) throws InterruptedException {
        waiters++;
        try {
            if (nanos < 0) {
                wait();
            } else {
                Monitors.awaitNanos(this, nanos);
            }
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

    /** Removes and returns the head if it's due; throws {@link NoSuchElementException} otherwise. */
    @Override
    public E remove() {
        E e = poll();
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    /** The head, whether or not it's due. */
    @Override
    public synchronized @Nullable E peek() {
        return queue.peek();
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
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /** Moves the due elements, at most {@code maxElements}, into {@code c}. */
    @SuppressWarnings("ReferenceEquality") // the contract is about this very collection
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this) {
            throw new IllegalArgumentException();
        }
        int n = 0;
        synchronized (this) {
            while (n < maxElements) {
                E head = queue.peek();
                if (head == null || head.getDelay(TimeUnit.NANOSECONDS) > 0) {
                    break;
                }
                c.add(Objects.requireNonNull(queue.poll()));
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
    public synchronized boolean remove(@Nullable Object o) {
        return o != null && queue.remove(o);
    }

    @Override
    public synchronized boolean contains(@Nullable Object o) {
        return o != null && queue.contains(o);
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

    // Removes this exact element, not an equal one: what an iterator's remove means.
    private synchronized void removeExact(Object o) {
        SnapshotIterator.removeExact(queue.iterator(), o);
    }
}
