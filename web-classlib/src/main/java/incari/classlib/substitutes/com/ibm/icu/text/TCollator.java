package incari.classlib.substitutes.com.ibm.icu.text;

import java.util.Comparator;
import java.util.Locale;

/**
 * ICU's {@code Collator} for the browser build, which has neither ICU nor {@code java.text.Collator}:
 * orders strings ignoring case first, then by exact text, for any locale. Close enough for the
 * sorted lists in the game's screens; a locale-aware order can come from the browser's Intl later.
 */
public final class TCollator implements Comparator<Object> {

    private static final TCollator INSTANCE = new TCollator();

    private TCollator() {}

    public static TCollator getInstance(Locale locale) {
        return INSTANCE;
    }

    public static TCollator getInstance() {
        return INSTANCE;
    }

    public int compare(String source, String target) {
        int folded = source.compareToIgnoreCase(target);
        return folded != 0 ? folded : source.compareTo(target);
    }

    @Override
    public int compare(Object source, Object target) {
        return compare((String) source, (String) target);
    }
}
