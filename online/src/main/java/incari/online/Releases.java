package incari.online;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Incari's release versions: one to four whole numbers joined by dots ({@code 0.2.0}). The service
 * names the latest in its {@code hello} answer; the client compares it with its own.
 */
public final class Releases {

    private static final Pattern VERSION = Pattern.compile("\\d{1,6}(\\.\\d{1,6}){0,3}");

    private Releases() {}

    /** {@code version} if it's a release version, else null: anything else the service sends is dropped. */
    public static @Nullable String valid(@Nullable String version) {
        return version != null && VERSION.matcher(version).matches() ? version : null;
    }

    /**
     * Whether {@code latest} comes after {@code current}. Missing parts count as 0, so {@code 1.2}
     * and {@code 1.2.0} are the same; either not a release version is never newer.
     */
    public static boolean newer(@Nullable String latest, @Nullable String current) {
        String later = valid(latest);
        String now = valid(current);
        if (later == null || now == null) {
            return false;
        }
        String[] a = later.split("\\.", -1);
        String[] b = now.split("\\.", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }
}
