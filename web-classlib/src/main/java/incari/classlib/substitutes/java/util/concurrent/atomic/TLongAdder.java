package incari.classlib.substitutes.java.util.concurrent.atomic;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code java.util.concurrent.atomic.LongAdder}. The JDK stripes the sum across cells to spread
 * contention between cores; the browser has one thread, so a single {@link AtomicLong} does, and
 * is also correct (just less scalable) on a JVM. Unlike the JDK's, {@link #sum} is exact even
 * during concurrent updates.
 */
public class TLongAdder extends Number implements Serializable {

    private static final long serialVersionUID = 1L;

    private final AtomicLong value = new AtomicLong();

    public TLongAdder() {}

    public void add(long x) {
        value.addAndGet(x);
    }

    public void increment() {
        value.incrementAndGet();
    }

    public void decrement() {
        value.decrementAndGet();
    }

    public long sum() {
        return value.get();
    }

    public void reset() {
        value.set(0L);
    }

    public long sumThenReset() {
        return value.getAndSet(0L);
    }

    @Override
    public String toString() {
        return Long.toString(sum());
    }

    @Override
    public long longValue() {
        return sum();
    }

    @Override
    public int intValue() {
        return (int) sum();
    }

    @Override
    public float floatValue() {
        return (float) sum();
    }

    @Override
    public double doubleValue() {
        return (double) sum();
    }
}
