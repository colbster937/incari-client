package incari.classlib.substitutes.java.net;

import java.io.IOException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.net.SocketException}, which TeaVM lacks: a connection failed. */
public class TSocketException extends IOException {

    @Serial
    private static final long serialVersionUID = -5935874303556886934L;

    public TSocketException() {}

    public TSocketException(@Nullable String message) {
        super(message);
    }

    public TSocketException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    public TSocketException(@Nullable Throwable cause) {
        super(cause);
    }
}
