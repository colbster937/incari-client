package incari.client.tier;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The device's quality tier (Potato, Standard and High), which Incari's budgets scale by: the
 * particle cap, the largest texture atlas. Until it's picked from the device, the page takes
 * {@link #STANDARD} unless {@code ?tier=} names another, and the desktop takes {@link #HIGH}, the
 * game as it is.
 */
public enum DeviceTier {
    POTATO,
    STANDARD,
    HIGH;

    private static volatile DeviceTier current = HIGH;

    public static DeviceTier current() {
        return current;
    }

    public static void set(DeviceTier tier) {
        current = tier;
    }

    /** The tier by name, any case; null if there's none by that name. */
    public static @Nullable DeviceTier named(String name) {
        for (DeviceTier tier : values()) {
            if (tier.name().equals(name.toUpperCase(Locale.ROOT))) {
                return tier;
            }
        }
        return null;
    }
}
