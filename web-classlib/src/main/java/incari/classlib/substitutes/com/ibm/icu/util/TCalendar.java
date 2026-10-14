package incari.classlib.substitutes.com.ibm.icu.util;

import java.util.Calendar;

/** ICU's {@code Calendar} for the browser build: a zone and a locale for a date format to use. */
public final class TCalendar {

    private final TTimeZone zone;
    private final TULocale locale;

    private TCalendar(TTimeZone zone, TULocale locale) {
        this.zone = zone;
        this.locale = locale;
    }

    public static TCalendar getInstance(TTimeZone zone, TULocale locale) {
        return new TCalendar(zone, locale);
    }

    public static TCalendar getInstance(TULocale locale) {
        return new TCalendar(TTimeZone.getDefault(), locale);
    }

    public TTimeZone getTimeZone() {
        return zone;
    }

    /** The equivalent {@link Calendar}, for {@code TSimpleDateFormat}. */
    public Calendar toJava() {
        return Calendar.getInstance(zone.toJava(), locale.toLocale());
    }
}
