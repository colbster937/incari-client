package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.CompletionException}: the unchecked wrapper a dependent
 * {@link TCompletableFuture} stage completes with when its source or its own computation fails.
 * Nothing browser-specific; TeaVM just doesn't ship it.
 */
public class TCompletionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected TCompletionException() {}

    protected TCompletionException(@Nullable String message) {
        super(message);
    }

    public TCompletionException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    public TCompletionException(@Nullable Throwable cause) {
        super(cause);
    }
}
