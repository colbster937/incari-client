package incari.classlib.substitutes.java.util.concurrent;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.CopyOnWriteArraySet}: a set backed by a
 * {@link CopyOnWriteArrayList} (which TeaVM has), so reads and iteration never lock and
 * iterators see the set as it was when they were made.
 */
public class TCopyOnWriteArraySet<E extends @Nullable Object> extends AbstractSet<E> {

    private final CopyOnWriteArrayList<E> list;

    public TCopyOnWriteArraySet() {
        list = new CopyOnWriteArrayList<>();
    }

    public TCopyOnWriteArraySet(Collection<? extends E> c) {
        list = new CopyOnWriteArrayList<>();
        list.addAllAbsent(c);
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(@Nullable Object o) {
        return list.contains(o);
    }

    @Override
    public Object[] toArray() {
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }

    @Override
    public void clear() {
        list.clear();
    }

    @Override
    public boolean remove(@Nullable Object o) {
        return list.remove(o);
    }

    @Override
    public boolean add(E e) {
        return list.addIfAbsent(e);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        return list.addAllAbsent(c) > 0;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return list.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return list.retainAll(c);
    }

    /** A snapshot iterator; it doesn't support {@code remove}. */
    @Override
    public Iterator<E> iterator() {
        return list.iterator();
    }

    // Not list.removeIf: TeaVM's list doesn't override it, and the default needs Iterator.remove.
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        List<@Nullable Object> doomed = new ArrayList<>();
        for (E e : list) {
            if (filter.test(e)) {
                doomed.add(e);
            }
        }
        return !doomed.isEmpty() && list.removeAll(doomed);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        list.forEach(action);
    }
}
