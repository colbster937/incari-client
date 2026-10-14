package incari.classlib.substitutes.java.net;

import java.net.SocketAddress;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.net.Proxy}, which TeaVM lacks. The game only carries one around (always
 * {@link #NO_PROXY} in the browser, which can't pick a proxy for its connections); it's a value.
 */
public class TProxy {

    public static final TProxy NO_PROXY = new TProxy();

    private final Type type;
    private final @Nullable SocketAddress address;

    private TProxy() {
        this.type = Type.DIRECT;
        this.address = null;
    }

    public TProxy(Type type, SocketAddress address) {
        if (type == Type.DIRECT) {
            throw new IllegalArgumentException("type DIRECT is not compatible with address " + address);
        }
        this.type = type;
        this.address = address;
    }

    public Type type() {
        return type;
    }

    public @Nullable SocketAddress address() {
        return address;
    }

    @Override
    public String toString() {
        return type == Type.DIRECT ? "DIRECT" : type + " @ " + address;
    }

    @Override
    public final boolean equals(@Nullable Object other) {
        return other instanceof TProxy proxy && proxy.type == type && Objects.equals(proxy.address, address);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(type, address);
    }

    /** How a connection goes through the proxy. */
    public enum Type {
        DIRECT,
        HTTP,
        SOCKS
    }
}
