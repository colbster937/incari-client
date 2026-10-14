package incari.classlib.substitutes.java.sql;

import java.io.Serial;

/**
 * {@code java.sql.Date}, which TeaVM lacks: a {@link java.util.Date} under another name. Gson's
 * optional SQL date support names it in signatures, and the browser build has no JDBC; nothing
 * else is here.
 */
@SuppressWarnings("JavaUtilDate") // It is a java.util.Date, by definition.
public class TDate extends java.util.Date {

    @Serial
    private static final long serialVersionUID = 1L;

    public TDate(long millis) {
        super(millis);
    }
}
