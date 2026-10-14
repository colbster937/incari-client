package incari.framework.setting;

/** The shape of the ids that end up as config keys and file names. */
public final class Ids {

    private static final int MAX_LENGTH = 48;

    private Ids() {}

    /**
     * Returns {@code id} if it's 1 to 48 lowercase letters, digits, {@code _} or {@code -}, starting
     * with a letter.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static String require(String id) {
        if (!valid(id)) {
            throw new IllegalArgumentException("Not a valid id (a-z, 0-9, _ and -, from a letter): \"" + id + "\"");
        }
        return id;
    }

    /** Whether {@code id} has the shape {@link #require} accepts. */
    public static boolean valid(String id) {
        if (id.isEmpty() || id.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean letter = c >= 'a' && c <= 'z';
            if (i == 0 ? !letter : !(letter || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
