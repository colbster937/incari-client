package incari.classlib.substitutes.com.mojang.text2speech;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mojang's text-to-speech {@code Narrator} for the browser build, which leaves out its native
 * speech engines. Until the Web Speech API version (the survey's narrator shim), there's no
 * narrator: {@link #getNarrator} is the inactive one, and the game shows narration as unavailable.
 */
public interface TNarrator {

    Logger LOGGER = LoggerFactory.getLogger("Narrator");

    TNarrator EMPTY = new TNarrator() {
        @Override
        public void say(String message, boolean interrupt, float volume) {}

        @Override
        public void clear() {}

        @Override
        public boolean active() {
            return false;
        }

        @Override
        public void destroy() {}
    };

    void say(String message, boolean interrupt, float volume);

    void clear();

    default boolean active() {
        return true;
    }

    void destroy();

    static TNarrator getNarrator() {
        return EMPTY;
    }

    /** A narrator that couldn't start. */
    class InitializeException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        public InitializeException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }

        public InitializeException(String message) {
            super(message);
        }
    }
}
