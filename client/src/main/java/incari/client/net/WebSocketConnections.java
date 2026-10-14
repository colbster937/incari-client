package incari.client.net;

import incari.net.WebSocketAddress;
import incari.net.WebSocketUrl;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Servers named by a {@code ws://} or {@code wss://} address (ADR 0009): resolving one needs no DNS
 * or SRV lookup, since the WebSocket resolves its host itself. Opening the connection is {@link
 * ServerConnections}' job.
 */
public final class WebSocketConnections {

    private WebSocketConnections() {}

    /** Whether {@code ServerNameResolver.resolveAddress} should hand this address to {@link #resolve}. */
    public static boolean isWebSocket(final ServerAddress address) {
        return address.webSocketUrl() != null;
    }

    /**
     * A WebSocket address resolves to itself, if the game's server block list allows its host: no
     * DNS lookup (the WebSocket resolves the host when it connects) and no SRV redirect, which
     * names a TCP endpoint.
     */
    public static Optional<ResolvedServerAddress> resolve(final ServerAddress address, final AddressCheck check) {
        WebSocketUrl url = address.webSocketUrl();
        if (url == null) {
            throw new IllegalArgumentException("Not a WebSocket address: " + address);
        }
        if (!check.isAllowed(address)) {
            return Optional.empty();
        }
        WebSocketAddress resolved = new WebSocketAddress(url);
        return Optional.of(new ResolvedServerAddress() {
            @Override
            public String getHostName() {
                return url.host();
            }

            @Override
            public String getHostIp() {
                // Unknown until the browser or the JDK connects; the block list checks the name.
                return url.host();
            }

            @Override
            public int getPort() {
                return url.port();
            }

            @Override
            public InetSocketAddress asInetSocketAddress() {
                return resolved;
            }
        });
    }
}
