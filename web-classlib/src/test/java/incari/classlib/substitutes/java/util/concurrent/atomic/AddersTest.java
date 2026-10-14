package incari.classlib.substitutes.java.util.concurrent.atomic;

import static incari.classlib.substitutes.java.util.concurrent.Concurrency.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class AddersTest {

    @Test
    void longAdderSumsConcurrentUpdates() throws InterruptedException {
        TLongAdder adder = new TLongAdder();
        runConcurrently(8, () -> {
            for (int i = 0; i < 10_000; i++) {
                adder.increment();
                adder.add(2);
                adder.decrement();
            }
        });
        assertThat(adder.sum()).isEqualTo(160_000L);
        assertThat(adder.intValue()).isEqualTo(160_000);
        assertThat(adder.doubleValue()).isEqualTo(160_000.0);
        assertThat(adder.floatValue()).isEqualTo(160_000f);
        assertThat(adder.toString()).isEqualTo("160000");
        assertThat(adder.sumThenReset()).isEqualTo(160_000L);
        assertThat(adder.longValue()).isZero();
        adder.add(4);
        adder.reset();
        assertThat(adder.sum()).isZero();
    }

    @Test
    void longAccumulatorKeepsTheMaximum() throws InterruptedException {
        TLongAccumulator max = new TLongAccumulator(Math::max, Long.MIN_VALUE);
        assertThat(max.get()).isEqualTo(Long.MIN_VALUE);
        runConcurrently(4, () -> {
            for (int i = 0; i < 10_000; i++) {
                max.accumulate(i);
            }
        });
        assertThat(max.get()).isEqualTo(9_999L);
        assertThat(max.intValue()).isEqualTo(9_999);
        assertThat(max.toString()).isEqualTo("9999");
        assertThat(max.getThenReset()).isEqualTo(9_999L);
        assertThat(max.longValue()).isEqualTo(Long.MIN_VALUE);
        max.accumulate(3);
        max.reset();
        assertThat(max.get()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void doubleAdderSumsConcurrentUpdates() throws InterruptedException {
        TDoubleAdder adder = new TDoubleAdder();
        runConcurrently(8, () -> {
            for (int i = 0; i < 10_000; i++) {
                adder.add(0.5);
            }
        });
        // Halves add up exactly.
        assertThat(adder.sum()).isEqualTo(40_000.0);
        assertThat(adder.longValue()).isEqualTo(40_000L);
        assertThat(adder.toString()).isEqualTo("40000.0");
        assertThat(adder.sumThenReset()).isEqualTo(40_000.0);
        assertThat(adder.doubleValue()).isZero();
    }

    @Test
    void doubleAccumulatorAppliesItsFunction() throws InterruptedException {
        TDoubleAccumulator product = new TDoubleAccumulator((x, y) -> x * y, 1.0);
        runConcurrently(4, () -> product.accumulate(2.0));
        assertThat(product.get()).isEqualTo(16.0);
        assertThat(product.intValue()).isEqualTo(16);
        assertThat(product.getThenReset()).isEqualTo(16.0);
        assertThat(product.get()).isEqualTo(1.0);
        product.accumulate(3.0);
        product.reset();
        assertThat(product.floatValue()).isEqualTo(1f);
    }
}
