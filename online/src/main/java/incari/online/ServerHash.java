package incari.online;

import java.util.Locale;

/**
 * Where the player is, as the service sees it (ADR 0058): SHA-256 of a fixed prefix and the address
 * they connected to, normalized so that the ways of writing one address agree. Two players see each
 * other only if their hashes match. The service never stores a plain list of servers; the hash keeps
 * plain addresses out of its database and logs (a determined attacker with a list of servers can
 * still match it).
 *
 * <p>Shared with the backend through {@code vectors.json}: change the prefix or the rules together.
 */
public final class ServerHash {

    /** Bumped if the rules below ever change, so old and new hashes never match by accident. */
    static final String PREFIX = "incari-server-v1\n";

    private static final int GAME_PORT = 25565;

    private ServerHash() {}

    /**
     * The hash of the address the player joined through, as 64 lowercase hex digits.
     *
     * @param address as the server list holds it: {@code host}, {@code host:port}, {@code ws://} or
     *     {@code wss://} URLs, or {@code relay:<code>} for a world shared through an Eagler relay
     */
    public static String of(String address) {
        return Sha256.hex(PREFIX + normalize(address));
    }

    /**
     * The address in its one written form: a lowercase host without a trailing dot, the port only if
     * it isn't the default, and a URL reduced to its host, port and path (no query, no trailing
     * slash). A relay's join code is kept as typed: codes may be case-sensitive.
     */
    static String normalize(String address) {
        String trimmed = address.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("relay:")) {
            return "relay:" + trimmed.substring("relay:".length()).strip();
        }
        if (lower.startsWith("ws://") || lower.startsWith("wss://")) {
            boolean secure = lower.startsWith("wss://");
            String rest = trimmed.substring(secure ? 6 : 5);
            int end = indexOfAny(rest, "?#");
            if (end >= 0) {
                rest = rest.substring(0, end);
            }
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "" : rest.substring(slash);
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return (secure ? "wss://" : "ws://") + hostAndPort(authority, secure ? 443 : 80) + path;
        }
        return hostAndPort(trimmed, GAME_PORT);
    }

    /** {@code host[:port]} or {@code [v6]:port}, lowercased, without the default port or a trailing dot. */
    private static String hostAndPort(String authority, int defaultPort) {
        String host = authority;
        String port = "";
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0) {
                port = host.substring(close + 1).startsWith(":") ? host.substring(close + 2) : "";
                host = host.substring(0, close + 1);
            }
        } else {
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.indexOf(':') == colon) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (port.isEmpty() || port.equals(Integer.toString(defaultPort))) {
            return host;
        }
        return host + ":" + port;
    }

    private static int indexOfAny(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
