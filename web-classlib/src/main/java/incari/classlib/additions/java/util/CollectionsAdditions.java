package incari.classlib.additions.java.util;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import org.jspecify.annotations.Nullable;

/** {@code java.util.Collections} methods TeaVM lacks. */
public final class CollectionsAdditions {

    private CollectionsAdditions() {}

    public static <K, V> SortedMap<K, V> unmodifiableSortedMap(SortedMap<K, ? extends V> map) {
        return new UnmodifiableSortedMap<>(map);
    }

    public static <T> SortedSet<T> unmodifiableSortedSet(SortedSet<T> set) {
        return new UnmodifiableSortedSet<>(set);
    }

    /** A read-only view of a sorted set; its subsets are read-only views too. */
    private static final class UnmodifiableSortedSet<T> extends java.util.AbstractSet<T> implements SortedSet<T> {
        private final SortedSet<T> set;

        UnmodifiableSortedSet(SortedSet<T> set) {
            this.set = set;
        }

        @Override
        public @Nullable Comparator<? super T> comparator() {
            return set.comparator();
        }

        @Override
        public SortedSet<T> subSet(T fromElement, T toElement) {
            return new UnmodifiableSortedSet<>(set.subSet(fromElement, toElement));
        }

        @Override
        public SortedSet<T> headSet(T toElement) {
            return new UnmodifiableSortedSet<>(set.headSet(toElement));
        }

        @Override
        public SortedSet<T> tailSet(T fromElement) {
            return new UnmodifiableSortedSet<>(set.tailSet(fromElement));
        }

        @Override
        public T first() {
            return set.first();
        }

        @Override
        public T last() {
            return set.last();
        }

        @Override
        public int size() {
            return set.size();
        }

        @Override
        public boolean contains(@Nullable Object element) {
            return set.contains(element);
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return Collections.unmodifiableCollection(set).iterator();
        }
    }

    /** A read-only view of a sorted map; its sub-maps are read-only views too. */
    private static final class UnmodifiableSortedMap<K, V> extends java.util.AbstractMap<K, V>
            implements SortedMap<K, V> {
        private final SortedMap<K, ? extends V> map;

        UnmodifiableSortedMap(SortedMap<K, ? extends V> map) {
            this.map = map;
        }

        @Override
        public @Nullable Comparator<? super K> comparator() {
            return map.comparator();
        }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) {
            return new UnmodifiableSortedMap<>(map.subMap(fromKey, toKey));
        }

        @Override
        public SortedMap<K, V> headMap(K toKey) {
            return new UnmodifiableSortedMap<>(map.headMap(toKey));
        }

        @Override
        public SortedMap<K, V> tailMap(K fromKey) {
            return new UnmodifiableSortedMap<>(map.tailMap(fromKey));
        }

        @Override
        public K firstKey() {
            return map.firstKey();
        }

        @Override
        public K lastKey() {
            return map.lastKey();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean containsKey(@Nullable Object key) {
            return map.containsKey(key);
        }

        @Override
        public @Nullable V get(@Nullable Object key) {
            return map.get(key);
        }

        @Override
        public Set<K> keySet() {
            return Collections.unmodifiableSet(map.keySet());
        }

        @Override
        public Collection<V> values() {
            return Collections.unmodifiableCollection(map.values());
        }

        @Override
        @SuppressWarnings("unchecked") // Read-only, so widening the value type is safe.
        public Set<Map.Entry<K, V>> entrySet() {
            return Collections.unmodifiableMap((Map<K, V>) map).entrySet();
        }
    }
}
