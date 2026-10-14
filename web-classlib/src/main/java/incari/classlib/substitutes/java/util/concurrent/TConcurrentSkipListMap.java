package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ConcurrentSkipListMap}, which TeaVM lacks, as a sorted map for one
 * thread at a time. Only Gson reaches it, to fill {@code ConcurrentNavigableMap} fields the game
 * doesn't have. Partial: the key sets are live, but sub-map and descending views aren't supported.
 */
public class TConcurrentSkipListMap<K, V> extends TreeMap<K, V> implements TConcurrentNavigableMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    public TConcurrentSkipListMap() {}

    public TConcurrentSkipListMap(@Nullable Comparator<? super K> comparator) {
        super(comparator);
    }

    public TConcurrentSkipListMap(Map<? extends K, ? extends V> map) {
        super(map);
    }

    public TConcurrentSkipListMap(SortedMap<K, ? extends V> map) {
        super(map);
    }

    @Override
    public TConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        throw views();
    }

    @Override
    public TConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        throw views();
    }

    @Override
    public TConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        throw views();
    }

    @Override
    public TConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
        throw views();
    }

    @Override
    public TConcurrentNavigableMap<K, V> headMap(K toKey) {
        throw views();
    }

    @Override
    public TConcurrentNavigableMap<K, V> tailMap(K fromKey) {
        throw views();
    }

    @Override
    public TConcurrentNavigableMap<K, V> descendingMap() {
        throw views();
    }

    // ConcurrentMap declares these four abstract again; one thread at a time, so Map's plain
    // check-then-act versions are atomic here.
    @Override
    public @Nullable V putIfAbsent(K key, V value) {
        V current = get(key);
        return current == null ? put(key, value) : current;
    }

    @Override
    public boolean remove(Object key, @Nullable Object value) {
        if (containsKey(key) && java.util.Objects.equals(get(key), value)) {
            remove(key);
            return true;
        }
        return false;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (containsKey(key) && java.util.Objects.equals(get(key), oldValue)) {
            put(key, newValue);
            return true;
        }
        return false;
    }

    @Override
    public @Nullable V replace(K key, V value) {
        return containsKey(key) ? put(key, value) : null;
    }

    @Override
    public NavigableSet<K> keySet() {
        return super.navigableKeySet();
    }

    private static UnsupportedOperationException views() {
        return new UnsupportedOperationException(
                "Views of a ConcurrentSkipListMap aren't supported in the browser build");
    }
}
