package incari.classlib.substitutes.java.util.concurrent;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ConcurrentMap}. Replaces TeaVM's, whose default
 * {@code computeIfAbsent} returns what {@code putIfAbsent} returns (null after inserting) instead
 * of the new value; every map without its own {@code computeIfAbsent}, such as Guava's weak-value
 * maps behind Minecraft's resource key interner, then answers null.
 */
public interface TConcurrentMap<K, V> extends Map<K, V> {

    @Override
    @Nullable
    V putIfAbsent(K key, V value);

    @Override
    boolean remove(Object key, @Nullable Object value);

    @Override
    boolean replace(K key, V oldValue, V newValue);

    @Override
    @Nullable
    V replace(K key, V value);

    @Override
    default V getOrDefault(Object key, V defaultValue) {
        V value = get(key);
        return value != null ? value : defaultValue;
    }

    @Override
    default void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Map.Entry<K, V> entry : entrySet()) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    @Override
    default void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        for (Map.Entry<K, V> entry : entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            while (!replace(key, value, function.apply(key, value))) {
                value = get(key);
                if (value == null) {
                    break;
                }
            }
        }
    }

    @Override
    default @Nullable V computeIfAbsent(K key, Function<? super K, ? extends @Nullable V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V created = mappingFunction.apply(key);
        if (created == null) {
            return null;
        }
        // putIfAbsent answers the value already there, or null when it put ours.
        V raced = putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    @Override
    default @Nullable V computeIfPresent(
            K key, BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        V oldValue;
        while ((oldValue = get(key)) != null) {
            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue != null ? replace(key, oldValue, newValue) : remove(key, oldValue)) {
                return newValue;
            }
        }
        return null;
    }

    @Override
    default @Nullable V compute(
            K key, BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        while (true) {
            V oldValue = get(key);
            V newValue = remappingFunction.apply(key, oldValue);
            if (oldValue != null) {
                if (newValue != null ? replace(key, oldValue, newValue) : remove(key, oldValue)) {
                    return newValue;
                }
            } else if (newValue == null || putIfAbsent(key, newValue) == null) {
                return newValue;
            }
        }
    }

    @Override
    default @Nullable V merge(
            K key, V value, BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        while (true) {
            V oldValue = get(key);
            if (oldValue == null) {
                if (putIfAbsent(key, value) == null) {
                    return value;
                }
                continue;
            }
            V newValue = remappingFunction.apply(oldValue, value);
            if (newValue != null ? replace(key, oldValue, newValue) : remove(key, oldValue)) {
                return newValue;
            }
        }
    }
}
