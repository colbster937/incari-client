package incari.classlib.substitutes.java.util.concurrent.atomic;

import static incari.classlib.substitutes.java.util.concurrent.Concurrency.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class AtomicArraysTest {

    @Test
    void integerArrayOperations() {
        TAtomicIntegerArray array = new TAtomicIntegerArray(3);
        assertThat(array.length()).isEqualTo(3);
        assertThat(array.get(0)).isZero();
        array.set(0, 5);
        assertThat(array.getAndSet(0, 7)).isEqualTo(5);
        assertThat(array.compareAndSet(0, 6, 8)).isFalse();
        assertThat(array.compareAndSet(0, 7, 8)).isTrue();
        assertThat(array.getAndIncrement(0)).isEqualTo(8);
        assertThat(array.incrementAndGet(0)).isEqualTo(10);
        assertThat(array.getAndDecrement(0)).isEqualTo(10);
        assertThat(array.decrementAndGet(0)).isEqualTo(8);
        assertThat(array.getAndAdd(1, 4)).isZero();
        assertThat(array.addAndGet(1, 4)).isEqualTo(8);
        assertThat(array.getAndUpdate(1, x -> x * 2)).isEqualTo(8);
        assertThat(array.updateAndGet(1, x -> x + 1)).isEqualTo(17);
        assertThat(array.getAndAccumulate(2, 3, Integer::sum)).isZero();
        assertThat(array.accumulateAndGet(2, 10, Math::max)).isEqualTo(10);
        assertThat(array.compareAndExchange(2, 10, 11)).isEqualTo(10);
        assertThat(array.compareAndExchange(2, 10, 12)).isEqualTo(11);
        assertThat(array.compareAndExchangeAcquire(2, 11, 1)).isEqualTo(11);
        assertThat(array.compareAndExchangeRelease(2, 1, 2)).isEqualTo(1);
        assertThat(array.weakCompareAndSetPlain(2, 2, 3)).isTrue();
        assertThat(array.weakCompareAndSetVolatile(2, 3, 4)).isTrue();
        assertThat(array.weakCompareAndSetAcquire(2, 4, 5)).isTrue();
        assertThat(array.weakCompareAndSetRelease(2, 5, 6)).isTrue();
        array.lazySet(2, 1);
        array.setPlain(2, array.getPlain(2) + 1);
        array.setOpaque(2, array.getOpaque(2) + 1);
        array.setRelease(2, array.getAcquire(2) + 1);
        assertThat(array.toString()).isEqualTo("[8, 17, 4]");
        assertThatThrownBy(() -> array.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void integerArrayCopiesItsSource() {
        int[] source = {1, 2};
        TAtomicIntegerArray array = new TAtomicIntegerArray(source);
        source[0] = 9;
        assertThat(array.get(0)).isEqualTo(1);
        assertThat(new TAtomicIntegerArray(0).toString()).isEqualTo("[]");
    }

    @Test
    void integerArrayCountsConcurrentIncrements() throws InterruptedException {
        TAtomicIntegerArray array = new TAtomicIntegerArray(4);
        runConcurrently(8, () -> {
            for (int i = 0; i < 20_000; i++) {
                array.incrementAndGet(i & 3);
                array.getAndUpdate(0, x -> x + 1);
            }
        });
        assertThat(array.get(0)).isEqualTo(8 * 5_000 + 8 * 20_000);
        assertThat(array.get(1)).isEqualTo(8 * 5_000);
        assertThat(array.get(3)).isEqualTo(8 * 5_000);
    }

    @Test
    void longArrayOperations() {
        TAtomicLongArray array = new TAtomicLongArray(new long[] {1L << 40, 0L});
        assertThat(array.length()).isEqualTo(2);
        assertThat(array.addAndGet(0, 1)).isEqualTo((1L << 40) + 1);
        assertThat(array.getAndSet(1, Long.MAX_VALUE)).isZero();
        assertThat(array.compareAndSet(1, Long.MAX_VALUE, -1L)).isTrue();
        assertThat(array.incrementAndGet(1)).isZero();
        assertThat(array.decrementAndGet(1)).isEqualTo(-1L);
        assertThat(array.accumulateAndGet(1, 5L, Math::max)).isEqualTo(5L);
        assertThat(array.updateAndGet(1, x -> x * 3)).isEqualTo(15L);
        assertThat(array.compareAndExchange(1, 15L, 16L)).isEqualTo(15L);
        assertThat(array.toString()).isEqualTo("[" + ((1L << 40) + 1) + ", 16]");
    }

    @Test
    void longArrayCountsConcurrentIncrements() throws InterruptedException {
        TAtomicLongArray array = new TAtomicLongArray(1);
        runConcurrently(8, () -> {
            for (int i = 0; i < 20_000; i++) {
                array.getAndAdd(0, 2);
            }
        });
        assertThat(array.get(0)).isEqualTo(320_000L);
    }

    @Test
    void referenceArrayComparesByIdentity() {
        TAtomicReferenceArray<String> array = new TAtomicReferenceArray<>(2);
        assertThat(array.get(0)).isNull();
        String a = new String("a");
        String equalA = new String("a");
        assertThat(array.compareAndSet(0, null, a)).isTrue();
        assertThat(array.compareAndSet(0, equalA, "b")).isFalse();
        assertThat(array.compareAndSet(0, a, "b")).isTrue();
        assertThat(array.getAndSet(0, "c")).isEqualTo("b");
        assertThat(array.updateAndGet(0, s -> s + "d")).isEqualTo("cd");
        assertThat(array.getAndUpdate(1, s -> "e")).isNull();
        assertThat(array.accumulateAndGet(1, "f", (x, y) -> x + y)).isEqualTo("ef");
        assertThat(array.getAndAccumulate(1, "g", (x, y) -> x + y)).isEqualTo("ef");
        assertThat(array.compareAndExchange(1, "nope", "h")).isEqualTo("efg");
        array.lazySet(1, null);
        assertThat(array.toString()).isEqualTo("[cd, null]");
    }

    @Test
    void referenceArrayCopiesItsSource() {
        Integer[] source = {1, 2, 3};
        TAtomicReferenceArray<Number> array = new TAtomicReferenceArray<>(source);
        source[0] = 9;
        array.set(1, 2.5); // a Number that couldn't go in the Integer[] source
        assertThat(array.get(0)).isEqualTo(1);
        assertThat(array.get(1)).isEqualTo(2.5);
    }

    @Test
    void referenceArrayUpdatesConcurrently() throws InterruptedException {
        TAtomicReferenceArray<Integer> array = new TAtomicReferenceArray<>(new Integer[] {0});
        runConcurrently(8, () -> {
            for (int i = 0; i < 10_000; i++) {
                array.updateAndGet(0, x -> x == null ? 1 : x + 1);
            }
        });
        assertThat(array.get(0)).isEqualTo(80_000);
    }
}
