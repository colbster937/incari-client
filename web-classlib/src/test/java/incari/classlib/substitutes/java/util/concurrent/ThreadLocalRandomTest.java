package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ThreadLocalRandomTest {

    @Test
    void constructsAndRefusesSeeds() {
        TThreadLocalRandom random = TThreadLocalRandom.current();
        assertThatThrownBy(() -> random.setSeed(1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void staysInRange() {
        TThreadLocalRandom random = TThreadLocalRandom.current();
        for (int i = 0; i < 10_000; i++) {
            assertThat(random.nextInt(7)).isBetween(0, 6);
            assertThat(random.nextInt(-3, 4)).isBetween(-3, 3);
            assertThat(random.nextDouble()).isBetween(0.0, 1.0).isNotEqualTo(1.0);
            assertThat(random.nextLong(10, 20)).isBetween(10L, 19L);
        }
    }
}
