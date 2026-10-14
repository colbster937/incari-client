package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ConcurrentLinkedQueue}: an unbounded, non-blocking FIFO queue. The
 * JDK's is lock-free; here an {@link ArrayDeque} behind the queue's monitor does the same job
 * (monitors are cheap when uncontended, and the browser never contends). Iterators work on a
 * snapshot, so they never throw {@code ConcurrentModificationException}.
 */
public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> {

    private final ArrayDeque<E> items = new ArrayDeque<>();

    public TConcurrentLinkedQueue() {}

    public TConcurrentLinkedQueue(Collection<? extends E> c) {
        for (E e : c) {
            items.addLast(Objects.requireNonNull(e));
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
            items.addLast(e);
        }
        return true;
    }

    @Override
    public synchronized @Nullable E poll() {
        return items.pollFirst();
    }

    @Override
    public synchronized @Nullable E peek() {
        return items.peekFirst();
    }

    @Override
    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized boolean contains(@Nullable Object o) {
        return o != null && items.contains(o);
    }

    @Override
    public synchronized boolean remove(@Nullable Object o) {
        return o != null && items.remove(o);
    }

    @SuppressWarnings("ReferenceEquality") // the contract is about this very collection
    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (c == this) {
            throw new IllegalArgumentException();
        }
        Object[] added = c.toArray();
        for (Object e : added) {
            Objects.requireNonNull(e);
        }
        synchronized (this) {
            for (Object e : added) {
                items.addLast(Monitors.<E>cast(e));
            }
        }
        return added.length > 0;
    }

    @Override
    public synchronized void clear() {
        items.clear();
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
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (Object e : toArray()) {
            action.accept(Monitors.<E>cast(e));
        }
    }

    @Override
    public Iterator<E> iterator() {
        return new SnapshotIterator<>(toArray(), this::removeExact);
    }

    private synchronized void removeExact(Object o) {
        SnapshotIterator.removeExact(items.iterator(), o);
    }
}
