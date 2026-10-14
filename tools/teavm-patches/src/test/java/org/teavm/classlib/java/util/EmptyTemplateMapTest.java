package org.teavm.classlib.java.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The patched {@link TTemplateCollections}' map with no entries, on the JVM: looking a key up in it
 * answered with a division by zero, which WebAssembly traps on (the End's biome sounds did
 * it). In TeaVM's package, since the map class is package-private.
 */
class EmptyTemplateMapTest {

    @Test
    void anEmptyMapAnswersLookups() {
        TTemplateCollections.NEtriesMap<String, String> empty = new TTemplateCollections.NEtriesMap<>();

        assertThat(empty.get("anything")).isNull();
        assertThat(empty.containsKey("anything")).isFalse();
        assertThat(empty.entrySet().contains(new TTemplateCollections.ImmutableEntry<>("a", "b")))
                .isFalse();
        assertThat(empty.isEmpty()).isTrue();
    }

    @Test
    void aFullMapStillFindsItsKeys() {
        TTemplateCollections.NEtriesMap<String, Integer> map = new TTemplateCollections.NEtriesMap<>(
                new TTemplateCollections.ImmutableEntry<>("a", 1), new TTemplateCollections.ImmutableEntry<>("b", 2));

        assertThat(map.get("a")).isEqualTo(1);
        assertThat(map.get("b")).isEqualTo(2);
        assertThat(map.get("c")).isNull();
    }
}
