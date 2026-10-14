package incari.classlib.substitutes.com.ibm.icu.util;

import java.util.TimeZone;
import org.jspecify.annotations.Nullable;

/** ICU's {@code TimeZone} for the browser build, over {@link java.util.TimeZone}. */
public final class TTimeZone {

    public static final TTimeZone UNKNOWN_ZONE = new TTimeZone(TimeZone.getTimeZone("GMT"), "Etc/Unknown");

    private final TimeZone zone;
    private final String id;

    private TTimeZone(TimeZone zone, String id) {
        this.zone = zone;
        this.id = id;
    }

    /** The zone with this id, or {@link #UNKNOWN_ZONE}. */
    public static TTimeZone getTimeZone(String id) {
        TimeZone zone = TimeZone.getTimeZone(id);
        // java.util answers GMT for ids it doesn't know; ICU answers the unknown zone.
        if (zone.getID().equals("GMT") && !id.equals("GMT")) {
            return UNKNOWN_ZONE;
        }
        return new TTimeZone(zone, id);
    }

    public static TTimeZone getDefault() {
        TimeZone zone = TimeZone.getDefault();
        return new TTimeZone(zone, zone.getID());
    }

    public String getID() {
        return id;
    }

    TimeZone toJava() {
        return zone;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TTimeZone that && that.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
