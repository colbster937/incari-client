package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.atomic.AtomicReferenceArray}. There are no VarHandles in the browser,
 * so every access synchronizes on the (private) backing array. On the page's one thread that lock
 * is never contended; on a JVM it gives each access volatile strength, which is at least what every
 * access mode promises. Comparisons are by identity ({@code ==}), as in the JDK. The update
 * functions run outside the lock and retry on a lost race, as the JDK's may.
 */
// serial: the elements may be anything, serializable or not, as in the JDK's own array of Object.
// ReferenceEquality: compare-and-set compares by identity, as the JDK's does.
@SuppressWarnings({"serial", "ReferenceEquality"})
public class TAtomicReferenceArray<E extends @Nullable Object> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final @Nullable Object[] array;

    public TAtomicReferenceArray(int length) {
        array = new Object[length];
    }

    public TAtomicReferenceArray(E[] array) {
        // A fresh Object[], so storing any E works even if the caller passed a narrower array type.
        this.array = new Object[array.length];
        System.arraycopy(array, 0, this.array, 0, array.length);
    }

    public final int length() {
        return array.length;
    }

    public final @Nullable E get(int i) {
        synchronized (array) {
            return element(array[i]);
        }
    }

    public final void set(int i, @Nullable E newValue) {
        synchronized (array) {
            array[i] = newValue;
        }
    }

    public final void lazySet(int i, @Nullable E newValue) {
        set(i, newValue);
    }

    public final @Nullable E getAndSet(int i, @Nullable E newValue) {
        synchronized (array) {
            Object old = array[i];
            array[i] = newValue;
            return element(old);
        }
    }

    public final boolean compareAndSet(int i, @Nullable E expectedValue, @Nullable E newValue) {
        synchronized (array) {
            if (array[i] != expectedValue) {
                return false;
            }
            array[i] = newValue;
            return true;
        }
    }

    @Deprecated(since = "9")
    @SuppressWarnings("InlineMeSuggester") // a JDK method, kept as the JDK declares it
    public final boolean weakCompareAndSet(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final @Nullable E getAndUpdate(int i, UnaryOperator<@Nullable E> updateFunction) {
        while (true) {
            E prev = get(i);
            if (compareAndSet(i, prev, updateFunction.apply(prev))) {
                return prev;
            }
        }
    }

    public final @Nullable E updateAndGet(int i, UnaryOperator<@Nullable E> updateFunction) {
        while (true) {
            E prev = get(i);
            E next = updateFunction.apply(prev);
            if (compareAndSet(i, prev, next)) {
                return next;
            }
        }
    }

    public final @Nullable E getAndAccumulate(int i, @Nullable E x, BinaryOperator<@Nullable E> accumulatorFunction) {
        while (true) {
            E prev = get(i);
            if (compareAndSet(i, prev, accumulatorFunction.apply(prev, x))) {
                return prev;
            }
        }
    }

    public final @Nullable E accumulateAndGet(int i, @Nullable E x, BinaryOperator<@Nullable E> accumulatorFunction) {
        while (true) {
            E prev = get(i);
            E next = accumulatorFunction.apply(prev, x);
            if (compareAndSet(i, prev, next)) {
                return next;
            }
        }
    }

    @Override
    public String toString() {
        synchronized (array) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(array[i]);
            }
            return builder.append(']').toString();
        }
    }

    public final @Nullable E getPlain(int i) {
        return get(i);
    }

    public final void setPlain(int i, @Nullable E newValue) {
        set(i, newValue);
    }

    public final @Nullable E getOpaque(int i) {
        return get(i);
    }

    public final void setOpaque(int i, @Nullable E newValue) {
        set(i, newValue);
    }

    public final @Nullable E getAcquire(int i) {
        return get(i);
    }

    public final void setRelease(int i, @Nullable E newValue) {
        set(i, newValue);
    }

    public final @Nullable E compareAndExchange(int i, @Nullable E expectedValue, @Nullable E newValue) {
        synchronized (array) {
            Object witness = array[i];
            if (witness == expectedValue) {
                array[i] = newValue;
            }
            return element(witness);
        }
    }

    public final @Nullable E compareAndExchangeAcquire(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    public final @Nullable E compareAndExchangeRelease(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int i, @Nullable E expectedValue, @Nullable E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    // Only Es are ever stored: the constructors copy an E[] and every setter takes an E.
    @SuppressWarnings("unchecked")
    private @Nullable E element(@Nullable Object value) {
        return (E) value;
    }
}
