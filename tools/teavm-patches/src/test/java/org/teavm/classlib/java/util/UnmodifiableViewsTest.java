package org.teavm.classlib.java.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The patched {@link TCollections}' unmodifiable views, on the JVM: they answer lookups by asking
 * the collection they wrap rather than walking it (which made the game's model loading
 * quadratic), and are still unmodifiable.
 */
class UnmodifiableViewsTest {

    /** A map whose entry set can't be walked: a lookup that walks it fails the test. */
    private static TMap<String, Integer> unwalkableMap() {
        THashMap<String, Integer> map = new THashMap<>() {
            @Override
            public TSet<Entry<String, Integer>> entrySet() {
                throw new AssertionError("walked the entry set");
            }
        };
        map.put("a", 1);
        map.put("b", 2);
        return map;
    }

    @Test
    void anUnmodifiableMapAsksTheMapItWraps() {
        TMap<String, Integer> view = TCollections.unmodifiableMap(unwalkableMap());

        assertThat(view.get("a")).isEqualTo(1);
        assertThat(view.get("c")).isNull();
        assertThat(view.containsKey("b")).isTrue();
        assertThat(view.containsKey("c")).isFalse();
        assertThat(view.size()).isEqualTo(2);
        assertThat(view.isEmpty()).isFalse();
        assertThatThrownBy(() -> view.remove("a")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anUnmodifiableSetAsksTheSetItWraps() {
        THashSet<String> set = new THashSet<>() {
            @Override
            public TIterator<String> iterator() {
                throw new AssertionError("walked the set");
            }
        };
        set.add("a");
        TSet<String> view = TCollections.unmodifiableSet(set);

        assertThat(view.contains("a")).isTrue();
        assertThat(view.contains("b")).isFalse();
        assertThat(view.isEmpty()).isFalse();
        assertThat(TCollections.unmodifiableCollection(set).contains("a")).isTrue();
    }
}
