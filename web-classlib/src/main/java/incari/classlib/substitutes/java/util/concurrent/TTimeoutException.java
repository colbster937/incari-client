package incari.classlib.substitutes.java.util.concurrent;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.TimeoutException}. */
public class TTimeoutException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public TTimeoutException() {}

    public TTimeoutException(@Nullable String message) {
        super(message);
    }
}
