package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * {@code java.util.concurrent.atomic.AtomicIntegerArray}. There are no VarHandles in the browser,
 * so every access synchronizes on the (private) backing array. On the page's one thread that lock
 * is never contended; on a JVM it gives each access volatile strength, which is at least what every
 * access mode (plain, opaque, acquire/release) promises. The update functions run outside the lock
 * and retry on a lost race, as the JDK's may.
 */
public class TAtomicIntegerArray implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int[] array;

    public TAtomicIntegerArray(int length) {
        array = new int[length];
    }

    public TAtomicIntegerArray(int[] array) {
        this.array = array.clone();
    }

    public final int length() {
        return array.length;
    }

    public final int get(int i) {
        synchronized (array) {
            return array[i];
        }
    }

    public final void set(int i, int newValue) {
        synchronized (array) {
            array[i] = newValue;
        }
    }

    public final void lazySet(int i, int newValue) {
        set(i, newValue);
    }

    public final int getAndSet(int i, int newValue) {
        synchronized (array) {
            int old = array[i];
            array[i] = newValue;
            return old;
        }
    }

    public final boolean compareAndSet(int i, int expectedValue, int newValue) {
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
    public final boolean weakCompareAndSet(int i, int expectedValue, int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int i, int expectedValue, int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final int getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    public final int getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    public final int getAndAdd(int i, int delta) {
        synchronized (array) {
            int old = array[i];
            array[i] = old + delta;
            return old;
        }
    }

    public final int incrementAndGet(int i) {
        return addAndGet(i, 1);
    }

    public final int decrementAndGet(int i) {
        return addAndGet(i, -1);
    }

    public final int addAndGet(int i, int delta) {
        synchronized (array) {
            return array[i] += delta;
        }
    }

    public final int getAndUpdate(int i, IntUnaryOperator updateFunction) {
        while (true) {
            int prev = get(i);
            if (compareAndSet(i, prev, updateFunction.applyAsInt(prev))) {
                return prev;
            }
        }
    }

    public final int updateAndGet(int i, IntUnaryOperator updateFunction) {
        while (true) {
            int prev = get(i);
            int next = updateFunction.applyAsInt(prev);
            if (compareAndSet(i, prev, next)) {
                return next;
            }
        }
    }

    public final int getAndAccumulate(int i, int x, IntBinaryOperator accumulatorFunction) {
        while (true) {
            int prev = get(i);
            if (compareAndSet(i, prev, accumulatorFunction.applyAsInt(prev, x))) {
                return prev;
            }
        }
    }

    public final int accumulateAndGet(int i, int x, IntBinaryOperator accumulatorFunction) {
        while (true) {
            int prev = get(i);
            int next = accumulatorFunction.applyAsInt(prev, x);
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

    public final int getPlain(int i) {
        return get(i);
    }

    public final void setPlain(int i, int newValue) {
        set(i, newValue);
    }

    public final int getOpaque(int i) {
        return get(i);
    }

    public final void setOpaque(int i, int newValue) {
        set(i, newValue);
    }

    public final int getAcquire(int i) {
        return get(i);
    }

    public final void setRelease(int i, int newValue) {
        set(i, newValue);
    }

    public final int compareAndExchange(int i, int expectedValue, int newValue) {
        synchronized (array) {
            int witness = array[i];
            if (witness == expectedValue) {
                array[i] = newValue;
            }
            return witness;
        }
    }

    public final int compareAndExchangeAcquire(int i, int expectedValue, int newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    public final int compareAndExchangeRelease(int i, int expectedValue, int newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int i, int expectedValue, int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int i, int expectedValue, int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int i, int expectedValue, int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }
}
