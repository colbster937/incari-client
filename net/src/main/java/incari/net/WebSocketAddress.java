package incari.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * A server reached through a WebSocket, as the socket address the game's connection code passes
 * around. The game treats it like any other {@link InetSocketAddress} (its host name goes into the
 * handshake packet), while {@link NetConnectionChannel} sees the URL it stands for and opens a
 * WebSocket instead of a TCP socket.
 *
 * <p>No DNS lookup ever happens here: the platform's WebSocket resolves the host itself. The
 * {@link #getAddress() address} is a placeholder (0.0.0.0) carrying the host name, since an
 * unresolved {@code InetSocketAddress} can't be subclassed. Never connect to it as an IP.
 */
public final class WebSocketAddress extends InetSocketAddress {

    private static final long serialVersionUID = 1L;

    private final WebSocketUrl url;

    public WebSocketAddress(WebSocketUrl url) {
        super(placeholder(url.host()), url.port());
        this.url = url;
    }

    /** The URL the WebSocket connects to. */
    public WebSocketUrl url() {
        return url;
    }

    @Override
    public String toString() {
        return url.toString();
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
