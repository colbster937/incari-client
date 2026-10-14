package incari.classlib.substitutes.java.util.concurrent;

import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentMap;

/** {@code java.util.concurrent.ConcurrentNavigableMap}, which TeaVM lacks. */
public interface TConcurrentNavigableMap<K, V> extends ConcurrentMap<K, V>, NavigableMap<K, V> {
    @Override
    TConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive);

    @Override
    TConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive);

    @Override
    TConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive);

    @Override
    TConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey);

    @Override
    TConcurrentNavigableMap<K, V> headMap(K toKey);

    @Override
    TConcurrentNavigableMap<K, V> tailMap(K fromKey);

    @Override
    TConcurrentNavigableMap<K, V> descendingMap();

    @Override
    NavigableSet<K> navigableKeySet();

    @Override
    NavigableSet<K> keySet();

    @Override
    NavigableSet<K> descendingKeySet();
}
