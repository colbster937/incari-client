package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.BrokenBarrierException}, thrown by {@link TCyclicBarrier}. */
public class TBrokenBarrierException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public TBrokenBarrierException() {}

    public TBrokenBarrierException(@Nullable String message) {
        super(message);
    }
}
