package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.LinkedBlockingDeque}, replacing TeaVM's, which is a plain
 * {@code LinkedList} without the blocking API. An optionally bounded deque behind its own monitor,
 * built like {@link TLinkedBlockingQueue}; iterators work on a snapshot.
 */
public class TLinkedBlockingDeque<E> extends AbstractQueue<E> implements TBlockingDeque<E> {

    private final int capacity;
    private final ArrayDeque<E> items = new ArrayDeque<>();
    private int waiters;

    public TLinkedBlockingDeque() {
        this(Integer.MAX_VALUE);
    }

    public TLinkedBlockingDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
    }

    public TLinkedBlockingDeque(Collection<? extends E> c) {
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

    // Caller holds this monitor.
    private boolean link(E e, boolean first) {
        if (items.size() >= capacity) {
            return false;
        }
        if (first) {
            items.addFirst(e);
        } else {
            items.addLast(e);
        }
        signal();
        return true;
    }

    // Caller holds this monitor.
    private @Nullable E unlink(boolean first) {
        E e = first ? items.pollFirst() : items.pollLast();
        if (e != null) {
            signal();
        }
        return e;
    }

    // ---------------------------------------------------------------------------------------------
    // Inserting

    @Override
    public void addFirst(E e) {
        if (!offerFirst(e)) {
            throw new IllegalStateException("Deque full");
        }
    }

    @Override
    public void addLast(E e) {
        if (!offerLast(e)) {
            throw new IllegalStateException("Deque full");
        }
    }

    @Override
    public boolean offerFirst(E e) {
        Objects.requireNonNull(e);
        synchronized (this) {
            return link(e, true);
        }
    }

    @Override
    public boolean offerLast(E e) {
        Objects.requireNonNull(e);
        synchronized (this) {
            return link(e, false);
        }
    }

    @Override
    public void putFirst(E e) throws InterruptedException {
        put(e, true);
    }

    @Override
    public void putLast(E e) throws InterruptedException {
        put(e, false);
    }

    private void put(E e, boolean first) throws InterruptedException {
        Objects.requireNonNull(e);
        synchronized (this) {
            Monitors.checkInterrupt();
            while (!link(e, first)) {
                await(-1L);
            }
        }
    }

    @Override
    public boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException {
        return offer(e, true, unit.toNanos(timeout));
    }

    @Override
    public boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException {
        return offer(e, false, unit.toNanos(timeout));
    }

    private boolean offer(E e, boolean first, long nanos) throws InterruptedException {
        Objects.requireNonNull(e);
        synchronized (this) {
            Monitors.checkInterrupt();
            while (!link(e, first)) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = await(nanos);
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Removing

    @Override
    public E removeFirst() {
        E e = pollFirst();
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    @Override
    public E removeLast() {
        E e = pollLast();
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    @Override
    public synchronized @Nullable E pollFirst() {
        return unlink(true);
    }

    @Override
    public synchronized @Nullable E pollLast() {
        return unlink(false);
    }

    @Override
    public E takeFirst() throws InterruptedException {
        return take(true);
    }

    @Override
    public E takeLast() throws InterruptedException {
        return take(false);
    }

    private synchronized E take(boolean first) throws InterruptedException {
        Monitors.checkInterrupt();
        E e;
        while ((e = unlink(first)) == null) {
            await(-1L);
        }
        return e;
    }

    @Override
    public @Nullable E pollFirst(long timeout, TimeUnit unit) throws InterruptedException {
        return poll(true, unit.toNanos(timeout));
    }

    @Override
    public @Nullable E pollLast(long timeout, TimeUnit unit) throws InterruptedException {
        return poll(false, unit.toNanos(timeout));
    }

    private synchronized @Nullable E poll(boolean first, long nanos) throws InterruptedException {
        Monitors.checkInterrupt();
        E e;
        while ((e = unlink(first)) == null) {
            if (nanos <= 0) {
                return null;
            }
            nanos = await(nanos);
        }
        return e;
    }

    @Override
    public E getFirst() {
        E e = peekFirst();
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    @Override
    public E getLast() {
        E e = peekLast();
        if (e == null) {
            throw new NoSuchElementException();
        }
        return e;
    }

    @Override
    public synchronized @Nullable E peekFirst() {
        return items.peekFirst();
    }

    @Override
    public synchronized @Nullable E peekLast() {
        return items.peekLast();
    }

    @Override
    public synchronized boolean removeFirstOccurrence(@Nullable Object o) {
        if (o != null && items.removeFirstOccurrence(o)) {
            signal();
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean removeLastOccurrence(@Nullable Object o) {
        if (o != null && items.removeLastOccurrence(o)) {
            signal();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Queue and stack views

    @Override
    public boolean add(E e) {
        addLast(e);
        return true;
    }

    @Override
    public boolean offer(E e) {
        return offerLast(e);
    }

    @Override
    public void put(E e) throws InterruptedException {
        putLast(e);
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        return offerLast(e, timeout, unit);
    }

    @Override
    public E remove() {
        return removeFirst();
    }

    @Override
    public @Nullable E poll() {
        return pollFirst();
    }

    @Override
    public E take() throws InterruptedException {
        return takeFirst();
    }

    @Override
    public @Nullable E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pollFirst(timeout, unit);
    }

    @Override
    public E element() {
        return getFirst();
    }

    @Override
    public @Nullable E peek() {
        return peekFirst();
    }

    @Override
    public synchronized int remainingCapacity() {
        return capacity - items.size();
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
    public void push(E e) {
        addFirst(e);
    }

    @Override
    public E pop() {
        return removeFirst();
    }

    @Override
    public boolean remove(@Nullable Object o) {
        return removeFirstOccurrence(o);
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized boolean contains(@Nullable Object o) {
        return o != null && items.contains(o);
    }

    /** Adds every element of {@code c} at the end; throws IllegalStateException if it fills up. */
    @SuppressWarnings("ReferenceEquality") // the contract is about this very collection
    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (c == this) {
            throw new IllegalArgumentException();
        }
        boolean changed = false;
        for (E e : c) {
            addLast(e);
            changed = true;
        }
        return changed;
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
    public Iterator<E> iterator() {
        return new SnapshotIterator<>(toArray(), this::removeExact);
    }

    @Override
    public Iterator<E> descendingIterator() {
        Object[] snapshot = toArray();
        for (int i = 0, j = snapshot.length - 1; i < j; i++, j--) {
            Object tmp = snapshot[i];
            snapshot[i] = snapshot[j];
            snapshot[j] = tmp;
        }
        return new SnapshotIterator<>(snapshot, this::removeExact);
    }

    private synchronized void removeExact(Object o) {
        SnapshotIterator.removeExact(items.iterator(), o);
        signal();
    }
}
