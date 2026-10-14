package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ConcurrentLinkedDeque}: an unbounded, non-blocking deque. As with
 * {@link TConcurrentLinkedQueue}, an {@link ArrayDeque} behind the deque's monitor stands in for
 * the JDK's lock-free nodes, and iterators work on a snapshot.
 */
public class TConcurrentLinkedDeque<E> extends AbstractCollection<E> implements Deque<E> {

    private final ArrayDeque<E> items = new ArrayDeque<>();

    public TConcurrentLinkedDeque() {}

    public TConcurrentLinkedDeque(Collection<? extends E> c) {
        for (E e : c) {
            items.addLast(Objects.requireNonNull(e));
        }
    }

    @Override
    public void addFirst(E e) {
        Objects.requireNonNull(e);
        synchronized (this) {
            items.addFirst(e);
        }
    }

    @Override
    public void addLast(E e) {
        Objects.requireNonNull(e);
        synchronized (this) {
            items.addLast(e);
        }
    }

    @Override
    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    @Override
    public boolean offerLast(E e) {
        addLast(e);
        return true;
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
    public synchronized @Nullable E pollFirst() {
        return items.pollFirst();
    }

    @Override
    public synchronized @Nullable E pollLast() {
        return items.pollLast();
    }

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
    public boolean offer(E e) {
        return offerLast(e);
    }

    @Override
    public boolean add(E e) {
        return offerLast(e);
    }

    @Override
    public @Nullable E poll() {
        return pollFirst();
    }

    @Override
    public @Nullable E peek() {
        return peekFirst();
    }

    @Override
    public E remove() {
        return removeFirst();
    }

    @Override
    public E pop() {
        return removeFirst();
    }

    @Override
    public E element() {
        return getFirst();
    }

    @Override
    public void push(E e) {
        addFirst(e);
    }

    @Override
    public synchronized boolean removeFirstOccurrence(@Nullable Object o) {
        return o != null && items.removeFirstOccurrence(o);
    }

    @Override
    public synchronized boolean removeLastOccurrence(@Nullable Object o) {
        return o != null && items.removeLastOccurrence(o);
    }

    @Override
    public synchronized boolean contains(@Nullable Object o) {
        return o != null && items.contains(o);
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
    public boolean remove(@Nullable Object o) {
        return removeFirstOccurrence(o);
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
    }
}
