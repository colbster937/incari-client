package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code java.util.concurrent.atomic.DoubleAdder}. One {@link AtomicLong} holding the sum's bits
 * instead of the JDK's striped cells: the browser has one thread, and a JVM gets correct if less
 * scalable updates. Additions apply one at a time, so the result is an ordinary floating-point
 * running sum.
 */
public class TDoubleAdder extends Number implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final long ZERO = Double.doubleToRawLongBits(0.0);

    private final AtomicLong bits = new AtomicLong(ZERO);

    public TDoubleAdder() {}

    public void add(double x) {
        bits.updateAndGet(sum -> Double.doubleToRawLongBits(Double.longBitsToDouble(sum) + x));
    }

    public double sum() {
        return Double.longBitsToDouble(bits.get());
    }

    public void reset() {
        bits.set(ZERO);
    }

    public double sumThenReset() {
        return Double.longBitsToDouble(bits.getAndSet(ZERO));
    }

    @Override
    public String toString() {
        return Double.toString(sum());
    }

    @Override
    public double doubleValue() {
        return sum();
    }

    @Override
    public long longValue() {
        return (long) sum();
    }

    @Override
    public int intValue() {
        return (int) sum();
    }

    @Override
    public float floatValue() {
        return (float) sum();
    }
}
