package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleBinaryOperator;

/**
 * {@code java.util.concurrent.atomic.DoubleAccumulator}. One {@link AtomicLong} holding the value's
 * bits instead of the JDK's striped cells: the browser has one thread, and a JVM gets correct if
 * less scalable updates. The function may run more than once per update when threads race.
 */
// The function is whatever the caller passed, serializable or not, as with the JDK's.
@SuppressWarnings("serial")
public class TDoubleAccumulator extends Number implements Serializable {

    private static final long serialVersionUID = 1L;

    private final DoubleBinaryOperator function;
    private final long identityBits;
    private final AtomicLong bits;

    public TDoubleAccumulator(DoubleBinaryOperator accumulatorFunction, double identity) {
        this.function = accumulatorFunction;
        this.identityBits = Double.doubleToRawLongBits(identity);
        this.bits = new AtomicLong(identityBits);
    }

    public void accumulate(double x) {
        bits.updateAndGet(
                current -> Double.doubleToRawLongBits(function.applyAsDouble(Double.longBitsToDouble(current), x)));
    }

    public double get() {
        return Double.longBitsToDouble(bits.get());
    }

    public void reset() {
        bits.set(identityBits);
    }

    public double getThenReset() {
        return Double.longBitsToDouble(bits.getAndSet(identityBits));
    }

    @Override
    public String toString() {
        return Double.toString(get());
    }

    @Override
    public double doubleValue() {
        return get();
    }

    @Override
    public long longValue() {
        return (long) get();
    }

    @Override
    public int intValue() {
        return (int) get();
    }

    @Override
    public float floatValue() {
        return (float) get();
    }
}
