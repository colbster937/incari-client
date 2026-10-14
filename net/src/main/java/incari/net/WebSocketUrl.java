package incari.net;

import java.io.Serializable;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A server address written as a WebSocket URL, {@code ws://host[:port][/path]} or {@code wss://...}:
 * how players name a server the browser can reach. Parsed by hand rather than with {@link
 * java.net.URI}, so that it behaves the same in the browser build, and so that what it accepts is
 * exactly what a browser's {@code WebSocket} constructor will: no user info, no fragment.
 *
 * @param secure whether it's {@code wss://} (TLS)
 * @param host a host name, IPv4 literal or IPv6 literal (without brackets)
 * @param port the TCP port, the scheme's default (80 or 443) if the URL gives none
 * @param path the path and query, at least {@code "/"}
 */
public record WebSocketUrl(boolean secure, String host, int port, String path) implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String WS = "ws://";
    private static final String WSS = "wss://";

    public WebSocketUrl {
        if (host.isEmpty()) {
            throw new IllegalArgumentException("A WebSocket URL needs a host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("A path starts with /: " + path);
        }
    }

    /** Whether {@code text} is meant as a WebSocket URL (by its scheme), valid or not. */
    public static boolean isWebSocketUrl(@Nullable String text) {
        if (text == null) {
            return false;
        }
        String lower = text.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith(WS) || lower.startsWith(WSS);
    }

    /** Parses {@code text}, or returns null if it isn't a valid WebSocket URL. */
    public static @Nullable WebSocketUrl tryParse(@Nullable String text) {
        if (text == null || !isWebSocketUrl(text)) {
            return null;
        }
        try {
            return parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses a WebSocket URL.
     *
     * @throws IllegalArgumentException if it isn't one, with the reason
     */
    public static WebSocketUrl parse(String text) {
        String url = text.strip();
        String lower = url.toLowerCase(Locale.ROOT);
        boolean secure;
        String rest;
        if (lower.startsWith(WSS)) {
            secure = true;
            rest = url.substring(WSS.length());
        } else if (lower.startsWith(WS)) {
            secure = false;
            rest = url.substring(WS.length());
        } else {
            throw new IllegalArgumentException("Not a ws:// or wss:// URL: " + text);
        }
        int authorityEnd = firstOf(rest, "/?#");
        String authority = rest.substring(0, authorityEnd);
        String path = pathOf(rest.substring(authorityEnd));
        if (authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("User names and passwords aren't allowed in a server address");
        }

        String host;
        String portText = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed [ in " + text);
            }
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    throw new IllegalArgumentException("Unexpected text after ] in " + text);
                }
                portText = after.substring(1);
            }
            checkIpv6(host);
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0 && authority.indexOf(':', colon + 1) >= 0) {
                throw new IllegalArgumentException("An IPv6 address must be in brackets: " + text);
            }
            host = colon < 0 ? authority : authority.substring(0, colon);
            portText = colon < 0 ? null : authority.substring(colon + 1);
            checkHostName(host);
        }
        int port = portText == null ? (secure ? 443 : 80) : parsePort(portText);
        return new WebSocketUrl(secure, host, port, path);
    }

    /** Whether the port is the scheme's default, so the URL needn't spell it out. */
    public boolean hasDefaultPort() {
        return port == (secure ? 443 : 80);
    }

    /** The URL in canonical form: lower-case scheme, bracketed IPv6, no default port. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(secure ? WSS : WS);
        if (host.indexOf(':') >= 0) {
            out.append('[').append(host).append(']');
        } else {
            out.append(host);
        }
        if (!hasDefaultPort()) {
            out.append(':').append(port);
        }
        return out.append(path).toString();
    }

    private static int firstOf(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return text.length();
    }

    /** The path and query of what follows the authority; a fragment means nothing to a WebSocket. */
    private static String pathOf(String afterAuthority) {
        int fragment = afterAuthority.indexOf('#');
        String path = fragment < 0 ? afterAuthority : afterAuthority.substring(0, fragment);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= ' ' || c == 0x7F) {
                throw new IllegalArgumentException("Spaces and control characters aren't allowed in the path");
            }
        }
        if (path.isEmpty() || path.startsWith("?")) {
            return "/" + path;
        }
        return path;
    }

    private static int parsePort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            throw new IllegalArgumentException("Not a port: '" + text + "'");
        }
        int port = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Not a port: '" + text + "'");
            }
            port = port * 10 + (c - '0');
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        return port;
    }

    private static void checkHostName(String host) {
        if (host.isEmpty()) {
            throw new IllegalArgumentException("A WebSocket URL needs a host");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            // Letters beyond ASCII are fine: the browser and the JDK both apply IDNA.
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '_';
            if (!allowed) {
                throw new IllegalArgumentException("'" + c + "' isn't allowed in a host name");
            }
        }
    }

    private static void checkIpv6(String host) {
        if (host.isEmpty() || host.indexOf(':') < 0) {
            throw new IllegalArgumentException("Not an IPv6 address: [" + host + "]");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = c == ':' || c == '.' || Character.digit(c, 16) >= 0;
            if (!allowed) {
                throw new IllegalArgumentException("Not an IPv6 address: [" + host + "]");
            }
        }
    }
}
