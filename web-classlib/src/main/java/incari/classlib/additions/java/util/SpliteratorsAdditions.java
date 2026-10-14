package incari.classlib.additions.java.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/** {@code java.util.Spliterators} methods TeaVM lacks. */
public final class SpliteratorsAdditions {

    private SpliteratorsAdditions() {}

    public static <T> Spliterator<T> emptySpliterator() {
        return java.util.Spliterators.spliterator(new Object[0], Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static <T extends @Nullable Object> Iterator<T> iterator(Spliterator<? extends T> spliterator) {
        return new Iterator<>() {
            private boolean ready;
            private @Nullable T next;

            @Override
            public boolean hasNext() {
                if (!ready) {
                    ready = spliterator.tryAdvance(value -> next = value);
                }
                return ready;
            }

            @Override
            @SuppressWarnings("NullAway") // next holds what tryAdvance gave, which is a T.
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return next;
            }
        };
    }

    public static PrimitiveIterator.OfInt iterator(Spliterator.OfInt spliterator) {
        return new PrimitiveIterator.OfInt() {
            private boolean ready;
            private int next;

            @Override
            public boolean hasNext() {
                if (!ready) {
                    ready = spliterator.tryAdvance((IntConsumer) this::take);
                }
                return ready;
            }

            private void take(int value) {
                next = value;
            }

            @Override
            public int nextInt() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return next;
            }
        };
    }
}
