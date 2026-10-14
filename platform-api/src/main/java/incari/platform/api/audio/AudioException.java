package incari.platform.api.audio;

import org.jspecify.annotations.Nullable;

/** Sound output failed: a device couldn't be opened, or it lacks what the game needs. */
public class AudioException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AudioException(String message) {
        super(message);
    }

    public AudioException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
