package incari.platform.api;

import org.jspecify.annotations.Nullable;

/**
 * Where a platform reports what it's doing: the window it created, a device that failed. The entry
 * point passes one in that forwards to the game's logging, so platform modules need no logging
 * library of their own. May be called from any thread.
 */
@FunctionalInterface
public interface PlatformLog {

    /** Severity, from routine detail to failure. */
    enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /** Discards everything. For tests. */
    PlatformLog NONE = (level, message, error) -> {};

    void log(Level level, String message, @Nullable Throwable error);

    default void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    default void info(String message) {
        log(Level.INFO, message, null);
    }

    default void warn(String message) {
        log(Level.WARN, message, null);
    }

    default void error(String message, @Nullable Throwable error) {
        log(Level.ERROR, message, error);
    }
}
