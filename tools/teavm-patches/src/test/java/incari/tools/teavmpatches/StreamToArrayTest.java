package incari.tools.teavmpatches;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.teavm.classlib.java.util.TSpliterator;
import org.teavm.classlib.java.util.stream.impl.TStreamOverSpliterator;

/**
 * Runs the patched stream classes on the JVM. The tab list's {@code sorted().limit(80).toList()}
 * over a fastutil set crashed: the sorted stream reported its drained source's size, 0, and
 * {@code toArray} overflowed an array of that size.
 */
class StreamToArrayTest {

    @Test
    void sortedThenLimitedOverACountingDownSourceKeepsEveryElement() {
        var stream = new TStreamOverSpliterator<>(new CountingDown<>(List.of(5, 3, 9, 1, 7)));

        Object[] result = stream.sorted(Comparator.naturalOrder()).limit(80).toArray();

        assertThat(result).containsExactly(1, 3, 5, 7, 9);
    }

    @Test
    void sortedThenLimitedStopsAtTheLimit() {
        var stream = new TStreamOverSpliterator<>(new CountingDown<>(List.of(5, 3, 9, 1, 7)));

        Object[] result = stream.sorted(Comparator.naturalOrder()).limit(2).toArray();

        assertThat(result).containsExactly(1, 3);
    }

    @Test
    void anEstimateThatIsTooSmallStillFillsTheArray() {
        var elements = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        var stream = new TStreamOverSpliterator<>(new Underestimating<>(elements));

        Integer[] result = stream.toArray(Integer[]::new);

        assertThat(result).containsExactlyElementsOf(elements);
    }

    /** Estimates what's left, as fastutil's hash set spliterators do. */
    private static class CountingDown<T> implements TSpliterator<T> {
        private final List<T> elements;
        private int index;

        CountingDown(List<T> elements) {
            this.elements = elements;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (index == elements.size()) {
                return false;
            }
            action.accept(elements.get(index++));
            return true;
        }

        @Override
        public @Nullable TSpliterator<T> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return (long) elements.size() - index;
        }

        @Override
        public int characteristics() {
            return SIZED;
        }
    }

    /** Estimates one element, however many there are. */
    private static final class Underestimating<T> extends CountingDown<T> {
        Underestimating(List<T> elements) {
            super(elements);
        }

        @Override
        public long estimateSize() {
            return 1;
        }

        @Override
        public int characteristics() {
            return 0;
        }
    }
}
