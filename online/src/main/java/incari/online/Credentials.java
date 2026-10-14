package incari.online;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The rules for an Incari username and password (ADR 0058), checked as the player types so the
 * form can say what's wrong before anything is sent. The service checks them again, against a
 * longer list of common passwords; these are a courtesy, not the defence.
 */
public final class Credentials {

    public static final int MIN_USERNAME = 3;
    public static final int MAX_USERNAME = 16;
    public static final int MIN_PASSWORD = 10;
    public static final int MAX_PASSWORD = 128;

    /**
     * Passwords long enough to pass the length rule that people still pick most. The service holds
     * the 10,000 most common; this short list catches the obvious ones without a round trip.
     */
    private static final Set<String> COMMON = Set.of(
            "1234567890",
            "0987654321",
            "1234567891",
            "12345678910",
            "123456789a",
            "123456789q",
            "a123456789",
            "qwertyuiop",
            "qwerty1234",
            "qwerty123456",
            "1q2w3e4r5t",
            "1qaz2wsx3edc",
            "asdfghjkl1",
            "zxcvbnm123",
            "abcdefghij",
            "abc1234567",
            "password12",
            "password123",
            "password1234",
            "password1!",
            "p@ssw0rd12",
            "passw0rd123",
            "iloveyou12",
            "iloveyou123",
            "letmein123",
            "welcome123",
            "football12",
            "football123",
            "baseball12",
            "princess12",
            "sunshine12",
            "monkey1234",
            "dragon1234",
            "trustno1234",
            "minecraft1",
            "minecraft12",
            "minecraft123",
            "minecraft1234",
            "eaglercraft",
            "eaglercraft1",
            "eaglercraft123",
            "incariclient",
            "incari1234",
            "chromebook1",
            "chromebook123",
            "fortnite123",
            "roblox12345");

    private Credentials() {}

    /** Whether {@code c} may be in a username: ASCII letters, digits and underscores. */
    public static boolean usernameCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** What's wrong with {@code username}, or null if it's one the service could take. */
    public static @Nullable String usernameProblem(String username) {
        if (username.length() < MIN_USERNAME || username.length() > MAX_USERNAME) {
            return "A username is " + MIN_USERNAME + " to " + MAX_USERNAME + " characters";
        }
        for (int i = 0; i < username.length(); i++) {
            if (!usernameCharacter(username.charAt(i))) {
                return "Only letters, digits and underscores";
            }
        }
        return null;
    }

    /** What's wrong with {@code password} for {@code username}'s account, or null if it's fine. */
    public static @Nullable String passwordProblem(String password, String username) {
        if (password.length() < MIN_PASSWORD) {
            return "At least " + MIN_PASSWORD + " characters";
        }
        if (password.length() > MAX_PASSWORD) {
            return "At most " + MAX_PASSWORD + " characters";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || oneCharacter(lower) || counting(lower)) {
            return "Too common: anyone could guess it";
        }
        if (!username.isEmpty() && lower.contains(username.toLowerCase(Locale.ROOT))) {
            return "Don't put your username in it";
        }
        return null;
    }

    private static boolean oneCharacter(String text) {
        return text.chars().distinct().count() <= 2;
    }

    /** 0123456789..., abcdef..., or the same backwards. */
    private static boolean counting(String text) {
        int up = 0;
        int down = 0;
        for (int i = 1; i < text.length(); i++) {
            int step = text.charAt(i) - text.charAt(i - 1);
            up += step == 1 ? 1 : 0;
            down += step == -1 ? 1 : 0;
        }
        return up == text.length() - 1 || down == text.length() - 1;
    }
}
