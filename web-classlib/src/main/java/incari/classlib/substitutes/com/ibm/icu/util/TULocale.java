package incari.classlib.substitutes.com.ibm.icu.util;

import java.util.Locale;

/**
 * ICU's {@code ULocale}, for the browser build, which leaves ICU out. Only what the game uses (item
 * models that show the local time), over {@link Locale}.
 */
public final class TULocale {

    private final Locale locale;

    public TULocale(String localeId) {
        this.locale = Locale.forLanguageTag(localeId.replace('_', '-'));
    }

    private TULocale(Locale locale) {
        this.locale = locale;
    }

    public static TULocale getDefault() {
        return new TULocale(Locale.getDefault());
    }

    public static TULocale forLocale(Locale locale) {
        return new TULocale(locale);
    }

    public Locale toLocale() {
        return locale;
    }

    @Override
    public String toString() {
        return locale.toString();
    }
}
