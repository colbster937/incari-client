package incari.net.eagler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A password for an Eagler server that asks for one (ADR 0019), and the forms it's sent in.
 * EaglerXServer leaves checking to an auth plugin, which names one of three methods in the
 * handshake along with a salt; the client answers in that method's form:
 *
 * <ul>
 *   <li>{@link #EAGLER_SHA256}: SHA-256 of the password (as UTF-16 with a length prefix) and a fixed
 *       salt, then twice more with each half of the server's 64-byte salt and a second fixed salt;
 *   <li>{@link #AUTHME_SHA256}: AuthMe's hash, {@code hex(sha256(hex(sha256(password)) + salt))},
 *       which is what AuthMe stores after its {@code $SHA$salt$};
 *   <li>{@link #PLAINTEXT}: the password itself, as UTF-16, which the player must agree to first.
 * </ul>
 *
 * @param text the password as typed
 * @param plaintextAllowed whether the player agreed to send it unhashed if the server asks so
 */
public record EaglerPassword(String text, boolean plaintextAllowed) {

    public static final int NONE = 0x00;
    public static final int EAGLER_SHA256 = 0x01;
    public static final int AUTHME_SHA256 = 0x02;
    public static final int PLAINTEXT = 0xFF;

    // Protocol constants: every Eagler client and auth plugin uses these two salts.
    private static final byte[] SALT_BASE = bytes(
            117, 43, 1, 112, 75, 3, 188, 61, 121, 31, 34, 181, 234, 31, 247, 72, 12, 168, 138, 45, 143, 77, 118, 245,
            187, 242, 188, 219, 160, 235, 235, 68);
    private static final byte[] SALT_SAVE = bytes(
            49, 25, 39, 38, 253, 85, 70, 245, 71, 150, 253, 206, 4, 26, 198, 249, 145, 251, 232, 174, 186, 98, 27, 232,
            55, 144, 83, 21, 36, 55, 170, 118);
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** Whether this client can answer {@code method} at all. */
    public static boolean supports(int method) {
        return method == EAGLER_SHA256 || method == AUTHME_SHA256 || method == PLAINTEXT;
    }

    /**
     * What goes in the login request's password field for {@code method} and the server's {@code
     * salt}, or null if it can't be sent: an unknown method, a short salt, or plain text the player
     * hasn't agreed to.
     */
    byte @Nullable [] answer(int method, byte[] salt) {
        return switch (method) {
            case EAGLER_SHA256 -> salt.length < 64 ? null : eaglerSha256(text, salt);
            case AUTHME_SHA256 -> authMeSha256(text, salt);
            case PLAINTEXT -> plaintextAllowed && text.length() <= 127 ? utf16(text) : null;
            default -> null;
        };
    }

    static byte[] eaglerSha256(String password, byte[] salt) {
        MessageDigest digest = sha256();
        digest.update((byte) (password.length() >>> 8));
        digest.update((byte) password.length());
        digest.update(utf16(password));
        digest.update(SALT_SAVE);
        byte[] hash = digest.digest();
        digest.update(hash);
        digest.update(salt, 0, 32);
        digest.update(SALT_BASE);
        hash = digest.digest();
        digest.update(hash);
        digest.update(salt, 32, 32);
        digest.update(SALT_BASE);
        return digest.digest();
    }

    static byte[] authMeSha256(String password, byte[] salt) {
        MessageDigest digest = sha256();
        byte[] inner = hex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        digest.update(inner);
        digest.update(salt);
        return hex(digest.digest());
    }

    private static byte[] utf16(String text) {
        return text.getBytes(StandardCharsets.UTF_16BE);
    }

    private static byte[] hex(byte[] bytes) {
        byte[] out = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return out;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    // A record's generated toString would print the password into logs.
    @Override
    public String toString() {
        return "EaglerPassword[" + text.length() + " characters]";
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof EaglerPassword password
                && password.text.equals(text)
                && password.plaintextAllowed == plaintextAllowed;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] {text, plaintextAllowed});
    }
}
