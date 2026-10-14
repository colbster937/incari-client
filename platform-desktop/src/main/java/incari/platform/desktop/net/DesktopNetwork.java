package incari.platform.desktop.net;

import incari.platform.api.PlatformLog;
import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import java.net.http.HttpClient;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Server connections as plain TCP sockets, or WebSockets through the JDK's HTTP client. */
public final class DesktopNetwork implements PlatformNetwork {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final PlatformLog log;
    /** Shared by every WebSocket: it owns the selector thread and a connection pool. */
    private volatile @Nullable HttpClient http;

    public DesktopNetwork(PlatformLog log) {
        this.log = log;
    }

    @Override
    public NetConnection connect(String host, int port, NetConnection.Listener listener) {
        SocketConnection connection = new SocketConnection(host, port, listener, log);
        connection.start();
        return connection;
    }

    @Override
    public NetConnection openWebSocket(String url, NetConnection.Listener listener) {
        WebSocketConnection connection = new WebSocketConnection(url, listener, log);
        connection.start(httpClient());
        return connection;
    }

    private HttpClient httpClient() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                client = http;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    http = client;
                }
            }
        }
        return client;
    }
}
