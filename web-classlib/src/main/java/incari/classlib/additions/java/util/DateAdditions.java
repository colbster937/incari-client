package incari.classlib.additions.java.util;

import incari.classlib.InstanceMethod;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

/** {@code java.util.Date} methods TeaVM lacks: the bridges to {@code java.time}. */
@SuppressWarnings("JavaUtilDate") // These are Date's own methods.
public final class DateAdditions {

    private DateAdditions() {}

    public static Date from(Instant instant) {
        return new Date(instant.toEpochMilli());
    }

    @InstanceMethod
    public static Instant toInstant(Date self) {
        return Instant.ofEpochMilli(self.getTime());
    }

    /** {@code EEE MMM dd HH:mm:ss zzz yyyy}, as the JDK prints a date. */
    @InstanceMethod
    public static String toString(Date self) {
        return new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).format(self);
    }
}
