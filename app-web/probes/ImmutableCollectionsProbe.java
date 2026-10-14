import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regression probe for tools/teavm-patches' TTemplateCollections hashing (ADR 0034): the sets and
 * maps {@code Set.of}, {@code Set.copyOf}, {@code Map.of} and {@code Map.ofEntries} make for more
 * than two elements. Lookups now probe a table of indices; this checks they answer as a
 * {@code HashSet} or {@code HashMap} would, for hits and misses, with colliding hashes, for every
 * size up to 40, and that duplicates still throw (of) or are dropped (copyOf). Prints
 * "probe passed" when every answer agrees.
 */
public final class ImmutableCollectionsProbe {

    private ImmutableCollectionsProbe() {}

    /** A key whose hash is chosen, so keys can collide. */
    private record Key(int id, int hash) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.id == id;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    public static void main(String[] args) {
        String failure = null;
        for (int size = 0; size <= 40 && failure == null; size++) {
            for (int spread = 1; spread <= 3 && failure == null; spread++) {
                failure = check(size, spread);
            }
        }
        if (failure == null) {
            failure = duplicates();
        }
        System.out.println(failure == null ? "probe passed" : "probe failed: " + failure);
    }

    /** Keys 0..size-1 whose hashes are id / spread (several share one when spread > 1). */
    private static String check(int size, int spread) {
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            keys.add(new Key(i, i / spread));
        }
        Set<Key> copied = Set.copyOf(keys);
        Set<Key> of = Set.of(keys.toArray(new Key[0]));
        @SuppressWarnings("unchecked")
        Map.Entry<Key, Integer>[] entries = new Map.Entry[size];
        for (int i = 0; i < size; i++) {
            entries[i] = Map.entry(keys.get(i), i * 10);
        }
        Map<Key, Integer> map = Map.ofEntries(entries);
        Map<Key, Integer> mapCopy = Map.copyOf(new HashMap<>(map));
        Set<Key> reference = new HashSet<>(keys);
        if (copied.size() != size || of.size() != size || map.size() != size || mapCopy.size() != size) {
            return "size " + size + " spread " + spread + ": sizes " + copied.size() + ", " + of.size() + ", " + map.size();
        }
        if (!new HashSet<>(copied).equals(reference) || !new HashSet<>(of).equals(reference)
                || !new HashSet<>(map.keySet()).equals(reference)) {
            return "size " + size + " spread " + spread + ": iteration";
        }
        // Hits, and misses: ids past the end, some with a hash a present key has.
        for (int id = 0; id < size + 20; id++) {
            Key probe = new Key(id, id / spread);
            boolean present = id < size;
            Integer value = present ? id * 10 : null;
            if (copied.contains(probe) != present || of.contains(probe) != present
                    || map.containsKey(probe) != present || mapCopy.containsKey(probe) != present
                    || !java.util.Objects.equals(map.get(probe), value)
                    || !java.util.Objects.equals(mapCopy.get(probe), value)
                    || map.entrySet().contains(Map.entry(probe, id * 10)) != present
                    || (present && map.entrySet().contains(Map.entry(probe, -1)))) {
                return "size " + size + " spread " + spread + ": lookup of " + id;
            }
        }
        if (copied.contains("not a key") || map.containsKey("not a key") || map.get("not a key") != null) {
            return "size " + size + ": a key of another type";
        }
        return null;
    }

    private static String duplicates() {
        try {
            Set.of(new Key(1, 0), new Key(2, 0), new Key(1, 0));
            return "Set.of accepted a duplicate";
        } catch (IllegalArgumentException expected) {
            // As the JDK's.
        }
        try {
            Map.of(new Key(1, 0), 1, new Key(2, 0), 2, new Key(1, 0), 3);
            return "Map.of accepted a duplicate key";
        } catch (IllegalArgumentException expected) {
            // As the JDK's.
        }
        Set<Key> copied = Set.copyOf(List.of(new Key(1, 0), new Key(2, 0), new Key(1, 0), new Key(3, 7)));
        if (copied.size() != 3 || !copied.contains(new Key(3, 7)) || copied.contains(new Key(4, 7))) {
            return "Set.copyOf kept " + copied.size() + " of a list with a duplicate";
        }
        return null;
    }
}
