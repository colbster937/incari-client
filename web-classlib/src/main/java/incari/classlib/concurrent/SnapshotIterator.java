package incari.classlib.concurrent;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * The iterator the concurrent collection substitutes hand out: it walks a copy taken when it was
 * made, so it never throws {@code ConcurrentModificationException} (the JDK's weakly consistent
 * iterators promise that), and its {@code remove} asks the collection to drop that exact element.
 */
public final class SnapshotIterator<E> implements Iterator<E> {

    private final Object[] items;
    private final Consumer<Object> remover;
    private int next;
    private int last = -1;

    /** Iterates {@code items}; {@code remover} removes one of them from the backing collection. */
    public SnapshotIterator(Object[] items, Consumer<Object> remover) {
        this.items = items;
        this.remover = remover;
    }

    @Override
    public boolean hasNext() {
        return next < items.length;
    }

    @Override
    public E next() {
        if (next >= items.length) {
            throw new NoSuchElementException();
        }
        last = next;
        return Monitors.cast(items[next++]);
    }

    @Override
    public void remove() {
        if (last < 0) {
            throw new IllegalStateException();
        }
        remover.accept(items[last]);
        last = -1;
    }

    /** Removes the element that is {@code o} itself (not merely equal) from {@code it}'s source. */
    @SuppressWarnings("ReferenceEquality") // identity is the point
    public static void removeExact(Iterator<?> it, Object o) {
        while (it.hasNext()) {
            if (it.next() == o) {
                it.remove();
                return;
            }
        }
    }
}
