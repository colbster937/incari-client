package incari.classlib.substitutes.java.lang;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.lang.ExceptionInInitializerError}, which TeaVM lacks. */
public class TExceptionInInitializerError extends LinkageError {

    @Serial
    private static final long serialVersionUID = 1521711792217232256L;

    public TExceptionInInitializerError() {
        super(null, null);
    }

    public TExceptionInInitializerError(@Nullable Throwable thrown) {
        super(null, thrown);
    }

    public TExceptionInInitializerError(@Nullable String message) {
        super(message, null);
    }

    public @Nullable Throwable getException() {
        return getCause();
    }
}
