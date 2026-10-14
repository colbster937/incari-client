import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Regression probe for tools/teavm-patches' TArrayDeque fix: removing elements through an
 * {@code ArrayDeque}'s iterator, as the game's particle groups do every tick. TeaVM's iterator
 * skipped the element after one it removed and then returned null past the end, so the first
 * particle to expire in a world crashed the game. Checks every removal pattern of up to ten
 * elements, with the deque's contents wrapped around its array at several offsets. Prints
 * "probe passed" when every deque holds what's left in order.
 */
public final class ArrayDequeProbe {

    private ArrayDequeProbe() {}

    public static void main(String[] args) {
        String failure = null;
        for (int size = 1; size <= 10 && failure == null; size++) {
            for (int removed = 0; removed < 1 << size && failure == null; removed++) {
                for (int offset = 0; offset < 5 && failure == null; offset++) {
                    failure = check(size, removed, offset);
                }
            }
        }
        System.out.println(failure == null ? "probe passed" : "probe failed: " + failure);
    }

    /** Null if removing the elements whose bits are set in {@code removed} works, else what went wrong. */
    private static String check(int size, int removed, int offset) {
        ArrayDeque<Integer> deque = new ArrayDeque<>(8);
        for (int i = 0; i < offset; i++) {
            deque.add(-1);
        }
        for (int i = 0; i < offset; i++) {
            deque.poll();
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            deque.add(i);
            if ((removed & 1 << i) == 0) {
                expected.add(i);
            }
        }
        List<Integer> seen = new ArrayList<>();
        for (Iterator<Integer> iterator = deque.iterator(); iterator.hasNext(); ) {
            Integer value = iterator.next();
            seen.add(value);
            if (value != null && (removed & 1 << value) != 0) {
                iterator.remove();
            }
        }
        List<Integer> left = new ArrayList<>(deque);
        if (seen.size() != size || seen.contains(null) || !left.equals(expected)) {
            return "size " + size + ", removing " + Integer.toBinaryString(removed) + ", offset " + offset
                    + ": iterated " + seen + ", left " + left;
        }
        return null;
    }
}
