package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;

/**
 * {@code java.util.concurrent.atomic.LongAccumulator}. One {@link AtomicLong} instead of the JDK's
 * striped cells: the browser has one thread, and a JVM gets correct if less scalable updates. The
 * function may run more than once per update when threads race, which the JDK allows too.
 */
// The function is whatever the caller passed, serializable or not, as with the JDK's.
@SuppressWarnings("serial")
public class TLongAccumulator extends Number implements Serializable {

    private static final long serialVersionUID = 1L;

    private final LongBinaryOperator function;
    private final long identity;
    private final AtomicLong value;

    public TLongAccumulator(LongBinaryOperator accumulatorFunction, long identity) {
        this.function = accumulatorFunction;
        this.identity = identity;
        this.value = new AtomicLong(identity);
    }

    public void accumulate(long x) {
        value.accumulateAndGet(x, function);
    }

    public long get() {
        return value.get();
    }

    public void reset() {
        value.set(identity);
    }

    public long getThenReset() {
        return value.getAndSet(identity);
    }

    @Override
    public String toString() {
        return Long.toString(get());
    }

    @Override
    public long longValue() {
        return get();
    }

    @Override
    public int intValue() {
        return (int) get();
    }

    @Override
    public float floatValue() {
        return (float) get();
    }

    @Override
    public double doubleValue() {
        return (double) get();
    }
}
