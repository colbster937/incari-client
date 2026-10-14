package incari.platform.api;

import org.jspecify.annotations.Nullable;

/** The platform couldn't do something the game can't continue without (a window, a display). */
public class PlatformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlatformException(String message) {
        super(message);
    }

    public PlatformException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
