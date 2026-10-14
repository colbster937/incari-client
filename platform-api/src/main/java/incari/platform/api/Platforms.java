package incari.platform.api;

import org.jspecify.annotations.Nullable;

/**
 * Holds the running {@link Platform}. The entry point ({@code app-desktop} or {@code app-web})
 * installs it before the game starts; the game reads it through {@link #current}.
 *
 * <p>A static holder rather than dependency injection because the game's own code reaches the
 * platform from static contexts (Mojang's {@code Blaze3D}, {@code InputConstants}), and there is
 * exactly one platform per process.
 */
public final class Platforms {

    private static volatile @Nullable Platform current;

    private Platforms() {}

    /**
     * Installs the platform. Call once, before the game starts.
     *
     * @throws IllegalStateException if a platform is already installed
     */
    public static synchronized void install(Platform platform) {
        if (current != null) {
            throw new IllegalStateException("A platform is already installed: " + current.description());
        }
        current = platform;
    }

    /**
     * The installed platform.
     *
     * @throws IllegalStateException if none is installed yet, which means the game was started
     *     without going through an Incari entry point
     */
    public static Platform current() {
        Platform platform = current;
        if (platform == null) {
            throw new IllegalStateException(
                    "No platform installed. Start the game through app-desktop or app-web, not its own Main.");
        }
        return platform;
    }

    /** Whether a platform is installed. */
    public static boolean isInstalled() {
        return current != null;
    }

    /** Removes the installed platform. For tests only: a real run never changes platform. */
    public static synchronized void uninstallForTests() {
        current = null;
    }
}
