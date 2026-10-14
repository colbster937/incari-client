package incari.classlib.additions.java.lang;

/** {@code java.lang.Character} methods TeaVM lacks. */
public final class CharacterAdditions {

    private CharacterAdditions() {}

    public static String toString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /**
     * Looks a character up by its Unicode name. The browser build carries no Unicode name table, so
     * every name is unknown; SNBT's {@code \N{...}} escapes fail to parse, as an unknown name would.
     */
    public static int codePointOf(String name) {
        throw new IllegalArgumentException("Unrecognized character name: " + name);
    }
}
