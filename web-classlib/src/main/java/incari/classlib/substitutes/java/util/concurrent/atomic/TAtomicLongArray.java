package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * {@code java.util.concurrent.atomic.AtomicLongArray}. There are no VarHandles in the browser,
 * so every access synchronizes on the (private) backing array. On the page's one thread that lock
 * is never contended; on a JVM it gives each access volatile strength, which is at least what every
 * access mode (plain, opaque, acquire/release) promises. The update functions run outside the lock
 * and retry on a lost race, as the JDK's may.
 */
public class TAtomicLongArray implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long[] array;

    public TAtomicLongArray(int length) {
        array = new long[length];
    }

    public TAtomicLongArray(long[] array) {
        this.array = array.clone();
    }

    public final int length() {
        return array.length;
    }

    public final long get(int i) {
        synchronized (array) {
            return array[i];
        }
    }

    public final void set(int i, long newValue) {
        synchronized (array) {
            array[i] = newValue;
        }
    }

    public final void lazySet(int i, long newValue) {
        set(i, newValue);
    }

    public final long getAndSet(int i, long newValue) {
        synchronized (array) {
            long old = array[i];
            array[i] = newValue;
            return old;
        }
    }

    public final boolean compareAndSet(int i, long expectedValue, long newValue) {
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
    public final boolean weakCompareAndSet(int i, long expectedValue, long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetPlain(int i, long expectedValue, long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final long getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    public final long getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    public final long getAndAdd(int i, long delta) {
        synchronized (array) {
            long old = array[i];
            array[i] = old + delta;
            return old;
        }
    }

    public final long incrementAndGet(int i) {
        return addAndGet(i, 1);
    }

    public final long decrementAndGet(int i) {
        return addAndGet(i, -1);
    }

    public final long addAndGet(int i, long delta) {
        synchronized (array) {
            return array[i] += delta;
        }
    }

    public final long getAndUpdate(int i, LongUnaryOperator updateFunction) {
        while (true) {
            long prev = get(i);
            if (compareAndSet(i, prev, updateFunction.applyAsLong(prev))) {
                return prev;
            }
        }
    }

    public final long updateAndGet(int i, LongUnaryOperator updateFunction) {
        while (true) {
            long prev = get(i);
            long next = updateFunction.applyAsLong(prev);
            if (compareAndSet(i, prev, next)) {
                return next;
            }
        }
    }

    public final long getAndAccumulate(int i, long x, LongBinaryOperator accumulatorFunction) {
        while (true) {
            long prev = get(i);
            if (compareAndSet(i, prev, accumulatorFunction.applyAsLong(prev, x))) {
                return prev;
            }
        }
    }

    public final long accumulateAndGet(int i, long x, LongBinaryOperator accumulatorFunction) {
        while (true) {
            long prev = get(i);
            long next = accumulatorFunction.applyAsLong(prev, x);
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

    public final long getPlain(int i) {
        return get(i);
    }

    public final void setPlain(int i, long newValue) {
        set(i, newValue);
    }

    public final long getOpaque(int i) {
        return get(i);
    }

    public final void setOpaque(int i, long newValue) {
        set(i, newValue);
    }

    public final long getAcquire(int i) {
        return get(i);
    }

    public final void setRelease(int i, long newValue) {
        set(i, newValue);
    }

    public final long compareAndExchange(int i, long expectedValue, long newValue) {
        synchronized (array) {
            long witness = array[i];
            if (witness == expectedValue) {
                array[i] = newValue;
            }
            return witness;
        }
    }

    public final long compareAndExchangeAcquire(int i, long expectedValue, long newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    public final long compareAndExchangeRelease(int i, long expectedValue, long newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetVolatile(int i, long expectedValue, long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetAcquire(int i, long expectedValue, long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final boolean weakCompareAndSetRelease(int i, long expectedValue, long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }
}
