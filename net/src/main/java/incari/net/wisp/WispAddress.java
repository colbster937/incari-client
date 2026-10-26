package incari.net.wisp;

import incari.net.WebSocketUrl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * A Java server reached through a Wisp proxy, as the socket address the game's connection code
 * passes around. The game sees the server's own host and port (they go into its handshake packet);
 * {@code NetConnectionChannel} sees the proxy and opens a {@link WispConnection}.
 *
 * <p>No DNS lookup happens here: the proxy resolves the host. The {@link #getAddress() address} is a
 * placeholder (0.0.0.0) carrying the host name, as in {@code WebSocketAddress}. Never connect to it
 * as an IP.
 */
public final class WispAddress extends InetSocketAddress {

    private static final long serialVersionUID = 1L;
    /** The port a server address without one gets; only those look for an SRV record, as the game does. */
    private static final int DEFAULT_PORT = 25565;

    private final WebSocketUrl proxy;
    private final boolean lookUpSrv;

    /**
     * @param proxy the Wisp proxy's WebSocket URL
     * @param host the server's host name or IP literal
     * @param port the server's port
     */
    public WispAddress(WebSocketUrl proxy, String host, int port) {
        super(placeholder(host), port);
        this.proxy = proxy;
        this.lookUpSrv = port == DEFAULT_PORT && !isIpLiteral(host);
    }

    /** The Wisp proxy's URL. */
    public WebSocketUrl proxy() {
        return proxy;
    }

    /**
     * Whether to look for the server's {@code _minecraft._tcp} SRV record before connecting, as the
     * game does for a host name given without a port.
     */
    public boolean lookUpSrv() {
        return lookUpSrv;
    }

    @Override
    public String toString() {
        return getHostString() + ":" + getPort() + " via " + proxy;
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress placeholder(String host) {
        try {
            // Only checks the array's length; resolves nothing.
            return InetAddress.getByAddress(host, new byte[4]);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
