package incari.classlib.substitutes.com.ibm.icu.text;

import java.util.Date;

/** ICU's {@code DateFormat} for the browser build; see {@link TSimpleDateFormat}. */
public abstract class TDateFormat {

    public abstract String format(Date date);
}
