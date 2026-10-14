package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.RejectedExecutionException}. */
public class TRejectedExecutionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TRejectedExecutionException() {}

    public TRejectedExecutionException(@Nullable String message) {
        super(message);
    }

    public TRejectedExecutionException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    public TRejectedExecutionException(@Nullable Throwable cause) {
        super(cause);
    }
}
