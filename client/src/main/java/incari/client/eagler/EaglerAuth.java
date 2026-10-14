package incari.client.eagler;

import incari.net.WebSocketUrl;
import incari.net.eagler.EaglerPassword;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Passwords for Eagler servers that ask for one (ADR 0019). A password is kept in memory for
 * the session, by server URL, and never written to disk: servers that want the player to stay
 * logged in set a cookie instead ({@link EaglerCookies}).
 *
 * <p>When a login fails for want of a password, the Eagler session records it here, and the
 * disconnection screen asks for one ({@link PasswordScreen}) instead of showing the refusal.
 */
public final class EaglerAuth {

    /** A server's request for a password, from the login that just failed. */
    public record Request(String server, int method, String message) {}

    private static final Object LOCK = new Object();
    private static final Map<String, EaglerPassword> PASSWORDS = new HashMap<>();
    private static @Nullable Request pending;

    private EaglerAuth() {}

    /** The password to offer {@code server}, if the player gave one this session. */
    public static @Nullable EaglerPassword password(String server) {
        synchronized (LOCK) {
            return PASSWORDS.get(key(server));
        }
    }

    /** Remembers the password for {@code server} for this session. */
    public static void remember(String server, EaglerPassword password) {
        synchronized (LOCK) {
            PASSWORDS.put(key(server), password);
        }
    }

    /** Forgets the password for {@code server}, as a new session would. */
    public static void forget(String server) {
        synchronized (LOCK) {
            PASSWORDS.remove(key(server));
        }
    }

    /** The Eagler session: {@code server} refused the login for want of a password. */
    static void required(String server, int method, String message) {
        synchronized (LOCK) {
            pending = new Request(server, method, message);
        }
    }

    /** The request the last failed login left, once; null if it wasn't a password matter. */
    public static @Nullable Request takePending() {
        synchronized (LOCK) {
            Request request = pending;
            pending = null;
            return request;
        }
    }

    /** The server's URL as the client writes it, so "ws://host:1" and "ws://HOST:1/" are one. */
    private static String key(String server) {
        WebSocketUrl url = WebSocketUrl.tryParse(server.trim());
        return (url != null ? url.toString() : server.trim()).toLowerCase(Locale.ROOT);
    }
}
