package incari.classlib.substitutes.java.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.UnknownCaller;
import org.junit.jupiter.api.Test;

class StackWalkerTest {

    @Test
    void seesNoFrames() {
        long frames = TStackWalker.getInstance().walk(stream -> stream.count());
        assertThat(frames).isZero();
        assertThat(TStackWalker.getInstance(TStackWalker.Option.RETAIN_CLASS_REFERENCE)
                        .getCallerClass())
                .isEqualTo(UnknownCaller.class);
        assertThatThrownBy(() -> TStackWalker.getInstance().getCallerClass())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
