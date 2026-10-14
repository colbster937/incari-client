package incari.classlib.additions.java.util.concurrent;

import incari.classlib.substitutes.java.util.concurrent.TConcurrentHashMap$KeySetView;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code java.util.concurrent.ConcurrentHashMap} methods TeaVM lacks. They name the class library's
 * {@code KeySetView} directly; the web build maps that name to the JDK's in this class too.
 */
public final class ConcurrentHashMapAdditions {

    private ConcurrentHashMapAdditions() {}

    public static <K> TConcurrentHashMap$KeySetView<K, Boolean> newKeySet() {
        return new TConcurrentHashMap$KeySetView<>(new ConcurrentHashMap<>(), Boolean.TRUE);
    }

    public static <K> TConcurrentHashMap$KeySetView<K, Boolean> newKeySet(int initialCapacity) {
        return new TConcurrentHashMap$KeySetView<>(new ConcurrentHashMap<>(initialCapacity), Boolean.TRUE);
    }
}
