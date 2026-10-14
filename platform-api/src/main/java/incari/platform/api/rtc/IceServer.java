package incari.platform.api.rtc;

import org.jspecify.annotations.Nullable;

/**
 * A STUN or TURN server a peer connection may use to reach the other side.
 *
 * @param url a {@code stun:} or {@code turn:} URL
 * @param username a TURN server's user name, or null
 * @param credential a TURN server's password, or null
 */
public record IceServer(
        String url, @Nullable String username, @Nullable String credential) {

    /**
     * Parses Eagler's form: a URL, or {@code url;username;password} for a TURN server. Returns
     * null for anything else.
     */
    public static @Nullable IceServer parseEagler(String text) {
        String[] parts = text.split(";", -1);
        if (parts.length == 1 && !parts[0].isBlank()) {
            return new IceServer(parts[0].trim(), null, null);
        }
        if (parts.length == 3) {
            return new IceServer(parts[0].trim(), parts[1], parts[2]);
        }
        return null;
    }
}
