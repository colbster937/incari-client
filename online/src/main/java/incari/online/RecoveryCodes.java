package incari.online;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Recovery codes as the player types them (ADR 0058): twelve characters of Crockford's base32 in
 * three groups ({@code XXXX-XXXX-XXXX}). The alphabet has no I, L, O or U, so a code read off paper
 * where one looks like a digit is read as the digit, as Crockford's rules say.
 */
public final class RecoveryCodes {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 12;

    private RecoveryCodes() {}

    /**
     * {@code typed} in its one written form, or null if it isn't a code: spaces and dashes are
     * ignored, case too, and I and L read as 1, O as 0.
     */
    public static @Nullable String normalize(String typed) {
        StringBuilder code = new StringBuilder(LENGTH + 2);
        int count = 0;
        for (int i = 0; i < typed.length(); i++) {
            char c = Character.toUpperCase(typed.charAt(i));
            if (c == ' ' || c == '-') {
                continue;
            }
            if (c == 'I' || c == 'L') {
                c = '1';
            } else if (c == 'O') {
                c = '0';
            }
            if (ALPHABET.indexOf(c) < 0 || count == LENGTH) {
                return null;
            }
            if (count == 4 || count == 8) {
                code.append('-');
            }
            code.append(c);
            count++;
        }
        return count == LENGTH ? code.toString() : null;
    }

    /** {@code code} as the service sent it, in the written form (upper case, grouped). */
    static String display(String code) {
        String normal = normalize(code);
        return normal == null ? code.toUpperCase(Locale.ROOT) : normal;
    }
}
