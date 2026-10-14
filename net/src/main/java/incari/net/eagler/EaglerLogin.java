package incari.net.eagler;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the client tells an Eagler server when it logs in.
 *
 * @param username the name to ask for; the server may assign another
 * @param minecraftProtocol the Minecraft protocol the game (or Via above it) will speak, which the
 *     proxy passes on to its backend; must fit 16 bits
 * @param brand the client's name, shown in the server's logs
 * @param version the client's version string
 * @param requestedServer the backend to join, or empty for the proxy's default
 * @param profileData up to eight named blobs sent after login: the skin ({@code skin_v2}), the cape
 *     ({@code cape_v1}) and so on ({@link EaglerTextures})
 * @param password the password to answer an auth plugin with, or null to offer none (ADR 0019)
 * @param cookie the cookie this server set earlier, sent back with the login, or null for none
 * @param capabilities the standard capabilities to ask for ({@link #VOICE}, {@link #COOKIE}...)
 */
public record EaglerLogin(
        String username,
        int minecraftProtocol,
        String brand,
        String version,
        String requestedServer,
        List<ProfileData> profileData,
        @Nullable EaglerPassword password,
        @Nullable Cookie cookie,
        int capabilities) {

    /** Eagler's handshake allows eight profile data entries. */
    public static final int MAX_PROFILE_DATA = 8;

    // Standard capabilities, as bits of the login request's mask (each asked for at version 0).
    /** WebRTC voice signalling. */
    public static final int VOICE = 1 << 1;
    /** Redirects to another server, during login and during play. */
    public static final int REDIRECT = 1 << 2;
    /** The server's pause menu buttons and their icons ({@link EaglerPauseMenu}). */
    public static final int PAUSE_MENU = 1 << 4;
    /** Server cookies: the server may store up to 255 bytes, sent back on the next login. */
    public static final int COOKIE = 1 << 6;

    /** A login without a password, a cookie or capabilities, as the first handshake sent. */
    public EaglerLogin(
            String username,
            int minecraftProtocol,
            String brand,
            String version,
            String requestedServer,
            List<ProfileData> profileData) {
        this(username, minecraftProtocol, brand, version, requestedServer, profileData, null, null, 0);
    }

    public EaglerLogin {
        if (minecraftProtocol < 0 || minecraftProtocol > 0xFFFF) {
            throw new IllegalArgumentException("The Eagler handshake can't carry protocol " + minecraftProtocol);
        }
        if (profileData.size() > MAX_PROFILE_DATA) {
            throw new IllegalArgumentException("At most " + MAX_PROFILE_DATA + " profile data entries");
        }
        profileData = List.copyOf(profileData);
    }

    /** A cookie to send back: the bytes the server set, at most 255. */
    public static final class Cookie {

        private final byte[] data;

        public Cookie(byte[] data) {
            if (data.length > 255) {
                throw new IllegalArgumentException("A cookie is at most 255 bytes, not " + data.length);
            }
            this.data = data.clone();
        }

        byte[] data() {
            return data;
        }
    }

    /** One named blob of profile data; the data must fit an unsigned short length. */
    public static final class ProfileData {

        private final String type;
        private final byte[] data;

        public ProfileData(String type, byte[] data) {
            if (data.length > 0xFFFF) {
                throw new IllegalArgumentException(type + " is " + data.length + " bytes, over 65535");
            }
            this.type = type;
            this.data = data.clone();
        }

        public String type() {
            return type;
        }

        /** The blob itself; don't change it. */
        byte[] data() {
            return data;
        }

        public int size() {
            return data.length;
        }
    }
}
