package incari.classlib.substitutes.java.net;

import java.io.Serial;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.net.InetSocketAddress}, which TeaVM lacks: an IP address or host name, and a port.
 * The game carries server addresses as these; a {@code ws://} server's is Incari's {@code
 * WebSocketAddress}, a subclass. Built from a name {@link TInetAddress} can't read, it's
 * unresolved, as after a failed lookup.
 */
public class TInetSocketAddress extends TSocketAddress {

    @Serial
    private static final long serialVersionUID = 5076001401234631237L;

    private final @Nullable String hostname;
    private final @Nullable TInetAddress address;
    private final int port;

    private TInetSocketAddress(@Nullable String hostname, @Nullable TInetAddress address, int port) {
        this.hostname = hostname;
        this.address = address;
        this.port = checkPort(port);
    }

    /** The wildcard address and {@code port}. */
    public TInetSocketAddress(int port) {
        this(null, wildcard(), port);
    }

    /** {@code address} (the wildcard address if null) and {@code port}. */
    public TInetSocketAddress(@Nullable TInetAddress address, int port) {
        this(null, address == null ? wildcard() : address, port);
    }

    /** Resolves {@code hostname} if it's an IP literal or "localhost"; otherwise it stays unresolved. */
    public TInetSocketAddress(String hostname, int port) {
        this(Objects.requireNonNull(hostname, "hostname can't be null"), resolveOrNull(hostname), port);
    }

    public static TInetSocketAddress createUnresolved(String host, int port) {
        return new TInetSocketAddress(Objects.requireNonNull(host, "hostname can't be null"), null, port);
    }

    public final int getPort() {
        return port;
    }

    public final @Nullable TInetAddress getAddress() {
        return address;
    }

    public final String getHostName() {
        if (hostname != null) {
            return hostname;
        }
        return Objects.requireNonNull(address).getHostName();
    }

    public final String getHostString() {
        if (hostname != null) {
            return hostname;
        }
        TInetAddress resolved = Objects.requireNonNull(address);
        return resolved.hostNameOrNull() != null ? resolved.getHostName() : resolved.getHostAddress();
    }

    public final boolean isUnresolved() {
        return address == null;
    }

    @Override
    public String toString() {
        String host = address == null ? getHostString() + "/<unresolved>" : address.toString();
        return host + ":" + port;
    }

    @Override
    public final boolean equals(@Nullable Object other) {
        if (!(other instanceof TInetSocketAddress that) || port != that.port) {
            return false;
        }
        if (address != null) {
            return address.equals(that.address);
        }
        // Unresolved names compare as host names do, ignoring case.
        return that.address == null && hostname != null && hostname.equalsIgnoreCase(that.hostname);
    }

    @Override
    public final int hashCode() {
        if (address != null) {
            return address.hashCode() + port;
        }
        return (hostname == null ? 0 : hostname.toLowerCase(Locale.ROOT).hashCode()) + port;
    }

    private static int checkPort(int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range:" + port);
        }
        return port;
    }

    private static TInetAddress wildcard() {
        return new TInet4Address(new byte[4], "0.0.0.0");
    }

    private static @Nullable TInetAddress resolveOrNull(String hostname) {
        try {
            return TInetAddress.getByName(hostname);
        } catch (TUnknownHostException e) {
            return null;
        }
    }
}
