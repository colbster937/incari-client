package incari.classlib.substitutes.java.net;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.net.Inet6Address}, which TeaVM lacks: a sixteen-byte address. Scopes aren't kept. */
public final class TInet6Address extends TInetAddress {

    @Serial
    private static final long serialVersionUID = 6880410070516793377L;

    TInet6Address(byte[] address, @Nullable String hostName) {
        super(address, hostName);
    }

    public int getScopeId() {
        return 0;
    }
}
