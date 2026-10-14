package incari.classlib.substitutes.java.net;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** {@code java.net.Inet4Address}, which TeaVM lacks: a four-byte address. */
public final class TInet4Address extends TInetAddress {

    @Serial
    private static final long serialVersionUID = 3286316764910316507L;

    TInet4Address(byte[] address, @Nullable String hostName) {
        super(address, hostName);
    }

    /** The address as a big-endian int, as the JDK hashes it. */
    @Override
    public int hashCode() {
        byte[] bytes = getAddress();
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return super.equals(other);
    }
}
