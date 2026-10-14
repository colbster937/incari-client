package incari.classlib.substitutes.java.net;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.net.ConnectException}, which TeaVM lacks: a connection couldn't be opened. */
public class TConnectException extends TSocketException {

    @Serial
    private static final long serialVersionUID = 3831404271622369215L;

    public TConnectException() {}

    public TConnectException(@Nullable String message) {
        super(message);
    }
}
