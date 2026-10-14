package incari.classlib.substitutes.java.sql;

import java.io.Serial;

/**
 * {@code java.sql.Timestamp}, which TeaVM lacks: a {@link java.util.Date} under another name. Gson's
 * optional SQL date support names it in signatures, and the browser build has no JDBC; nothing
 * else is here.
 */
@SuppressWarnings("JavaUtilDate") // It is a java.util.Date, by definition.
public class TTimestamp extends java.util.Date {

    @Serial
    private static final long serialVersionUID = 1L;

    public TTimestamp(long millis) {
        super(millis);
    }
}
