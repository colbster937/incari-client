package incari.classlib.substitutes.com.ibm.icu.text;

import incari.classlib.substitutes.com.ibm.icu.util.TCalendar;
import incari.classlib.substitutes.com.ibm.icu.util.TULocale;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * ICU's {@code SimpleDateFormat} for the browser build, over {@link java.text.SimpleDateFormat},
 * whose pattern letters agree with ICU's for the common fields.
 */
public class TSimpleDateFormat extends TDateFormat {

    private final SimpleDateFormat format;

    public TSimpleDateFormat(String pattern, TULocale locale) {
        this.format = new SimpleDateFormat(pattern, locale.toLocale());
    }

    public void setCalendar(TCalendar calendar) {
        format.setCalendar(calendar.toJava());
    }

    @Override
    public String format(Date date) {
        return format.format(date);
    }
}
