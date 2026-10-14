package incari.classlib.substitutes.com.ibm.icu.lang;

/**
 * ICU's {@code UCharacter} for the browser build, with the one method the game uses: the
 * mirror image of a bracket-like character in right-to-left text. Covers the ASCII and common
 * typographic pairs; other characters mirror to themselves.
 */
public final class TUCharacter {

    private static final String PAIRS = "()[]{}<>«»‹›⁅⁆⁽⁾₍₎≤≥";

    private TUCharacter() {}

    public static int getMirror(int ch) {
        int at = PAIRS.indexOf(ch);
        if (at < 0) {
            return ch;
        }
        return PAIRS.charAt(at % 2 == 0 ? at + 1 : at - 1);
    }
}
