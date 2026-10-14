package incari.classlib.substitutes.java.net;

import java.io.IOException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.net.UnknownHostException}, which TeaVM lacks: a host name that can't be resolved. */
public class TUnknownHostException extends IOException {

    @Serial
    private static final long serialVersionUID = -4639126076052875403L;

    public TUnknownHostException() {}

    public TUnknownHostException(@Nullable String message) {
        super(message);
    }
}
