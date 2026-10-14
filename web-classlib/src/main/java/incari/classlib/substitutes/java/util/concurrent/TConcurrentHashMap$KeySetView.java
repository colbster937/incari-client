package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ConcurrentHashMap.KeySetView}, which TeaVM's {@code ConcurrentHashMap}
 * lacks: here only the sets {@code ConcurrentHashMap.newKeySet} makes (from
 * {@code ConcurrentHashMapAdditions}), a set over a map's keys, each mapped to a fixed value. A
 * top-level class named for the nested one, so it takes the JDK name.
 */
@SuppressWarnings("TypeName") // The name is the JDK's nested class, as the class library maps it.
public final class TConcurrentHashMap$KeySetView<K, V> extends AbstractSet<K> implements Serializable {

    @Serial
    private static final long serialVersionUID = 7249069246763182397L;

    private final ConcurrentHashMap<K, V> map;

    @SuppressWarnings("serial") // The mapped value is the caller's; the JDK's view doesn't check it either.
    private final @Nullable V value;

    public TConcurrentHashMap$KeySetView(ConcurrentHashMap<K, V> map, @Nullable V value) {
        this.map = map;
        this.value = value;
    }

    public ConcurrentHashMap<K, V> getMap() {
        return map;
    }

    public @Nullable V getMappedValue() {
        return value;
    }

    @Override
    public boolean add(K key) {
        V mapped = value;
        if (mapped == null) {
            throw new UnsupportedOperationException();
        }
        return map.putIfAbsent(key, mapped) == null;
    }

    @Override
    public boolean addAll(Collection<? extends K> keys) {
        boolean changed = false;
        for (K key : keys) {
            changed |= add(key);
        }
        return changed;
    }

    @Override
    public boolean contains(@Nullable Object o) {
        return o != null && map.containsKey(o);
    }

    @Override
    public boolean remove(@Nullable Object o) {
        return o != null && map.remove(o) != null;
    }

    @Override
    public Iterator<K> iterator() {
        // Through Map: TeaVM's ConcurrentHashMap.keySet returns a Set, not the JDK's KeySetView.
        return ((Map<K, V>) map).keySet().iterator();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void clear() {
        map.clear();
    }
}
