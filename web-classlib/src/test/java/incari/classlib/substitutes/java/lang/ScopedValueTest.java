package incari.classlib.substitutes.java.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScopedValueTest {

    private static final TScopedValue<String> NAME = TScopedValue.newInstance();
    private static final TScopedValue<Integer> DEPTH = TScopedValue.newInstance();

    @Test
    void boundOnlyForTheCall() {
        assertThat(NAME.isBound()).isFalse();
        TScopedValue.where(NAME, "outer").run(() -> {
            assertThat(NAME.get()).isEqualTo("outer");
            TScopedValue.where(NAME, "inner").where(DEPTH, 2).run(() -> {
                assertThat(NAME.get()).isEqualTo("inner");
                assertThat(DEPTH.get()).isEqualTo(2);
            });
            assertThat(NAME.get()).isEqualTo("outer");
            assertThat(DEPTH.isBound()).isFalse();
        });
        assertThat(NAME.orElse("none")).isEqualTo("none");
        assertThatThrownBy(NAME::get).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void callReturnsAndRestoresOnThrow() throws Exception {
        assertThat(TScopedValue.where(NAME, "value").call(NAME::get)).isEqualTo("value");
        assertThatThrownBy(() -> TScopedValue.where(NAME, "x").run(() -> {
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");
        assertThat(NAME.isBound()).isFalse();
    }

    @Test
    void bindingsArePerThread() throws InterruptedException {
        AtomicReference<Boolean> seen = new AtomicReference<>();
        TScopedValue.where(NAME, "main").run(() -> {
            Thread other = new Thread(() -> seen.set(NAME.isBound()));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(seen.get()).isFalse();
    }
}
