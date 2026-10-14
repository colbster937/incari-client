package incari.classlib.substitutes.java.nio.file;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.nio.file.ProviderMismatchException}, which TeaVM lacks. */
public class TProviderMismatchException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 4990847485741612530L;

    public TProviderMismatchException() {}

    public TProviderMismatchException(@Nullable String message) {
        super(message);
    }
}
