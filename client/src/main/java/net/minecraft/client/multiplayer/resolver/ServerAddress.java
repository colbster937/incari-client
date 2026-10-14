package net.minecraft.client.multiplayer.resolver;

import com.google.common.net.HostAndPort;
import com.mojang.logging.LogUtils;
import incari.net.WebSocketUrl;
import java.net.IDN;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari's replacement for the game's server address (patches/classes.txt): what a player types
 * in the server list, either {@code host[:port]} for a TCP server, as in vanilla, or a {@code
 * ws://} / {@code wss://} URL for a server reached through a WebSocket (ADR 0009), which is the
 * only kind a browser can join. The game's API is unchanged; {@link #webSocketUrl} is new.
 */
public final class ServerAddress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 25565;
    private static final ServerAddress INVALID = new ServerAddress("server.invalid", DEFAULT_PORT);

    /** The TCP host and port; for a WebSocket address, the URL's, kept for the game's callers. */
    private final HostAndPort hostAndPort;

    private final @Nullable WebSocketUrl webSocket;

    /**
     * A TCP host and port, or, if {@code host} is a WebSocket URL, that URL (whose own port wins).
     * A server's transfer packet names its target this way, so a server can send a browser to
     * another WebSocket server.
     */
    public ServerAddress(final String host, final int port) {
        WebSocketUrl url = WebSocketUrl.tryParse(host);
        this.hostAndPort =
                url == null ? HostAndPort.fromParts(host, port) : HostAndPort.fromParts(url.host(), url.port());
        this.webSocket = url;
    }

    private ServerAddress(final HostAndPort hostAndPort) {
        this.hostAndPort = hostAndPort;
        this.webSocket = null;
    }

    private ServerAddress(final WebSocketUrl url) {
        this.hostAndPort = HostAndPort.fromParts(url.host(), url.port());
        this.webSocket = url;
    }

    /**
     * The host in ASCII form (IDNA applied), or "" if it can't be. For a WebSocket address, the
     * URL's host.
     */
    public String getHost() {
        String host = this.hostAndPort.getHost();
        if (isAscii(host)) {
            // What IDNA would return for it anyway; and java.net.IDN isn't in the browser build.
            return host;
        }
        try {
            return IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public int getPort() {
        return this.hostAndPort.getPort();
    }

    /** The WebSocket URL this address names, or null for a TCP address. */
    public @Nullable WebSocketUrl webSocketUrl() {
        return this.webSocket;
    }

    /** Reads an address as typed; an address that can't be read becomes a harmless invalid one. */
    public static ServerAddress parseString(final @Nullable String input) {
        if (input == null) {
            return INVALID;
        }
        if (WebSocketUrl.isWebSocketUrl(input)) {
            try {
                return new ServerAddress(WebSocketUrl.parse(input));
            } catch (IllegalArgumentException e) {
                LOGGER.info("Failed to parse WebSocket address {}: {}", input, e.getMessage());
                return INVALID;
            }
        }
        try {
            HostAndPort parsed = HostAndPort.fromString(input).withDefaultPort(DEFAULT_PORT);
            if (parsed.getHost().isEmpty()) {
                return INVALID;
            }
            return new ServerAddress(parsed);
        } catch (IllegalArgumentException e) {
            LOGGER.info("Failed to parse URL {}", input, e);
            return INVALID;
        }
    }

    /** Whether the server list should accept {@code input} as an address. */
    public static boolean isValidAddress(final String input) {
        if (WebSocketUrl.isWebSocketUrl(input)) {
            return WebSocketUrl.tryParse(input) != null;
        }
        try {
            String host = HostAndPort.fromString(input).getHost();
            if (host.isEmpty()) {
                return false;
            }
            if (!isAscii(host)) {
                IDN.toASCII(host);
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A port number as typed, or the default port if it isn't one. */
    public static int parsePort(final String str) {
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private static boolean isAscii(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return this.webSocket != null ? this.webSocket.toString() : this.hostAndPort.toString();
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServerAddress that)) {
            return false;
        }
        return this.webSocket != null
                ? this.webSocket.equals(that.webSocket)
                : that.webSocket == null && this.hostAndPort.equals(that.hostAndPort);
    }

    @Override
    public int hashCode() {
        return this.webSocket != null ? this.webSocket.hashCode() : this.hostAndPort.hashCode();
    }
}
