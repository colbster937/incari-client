package incari.tools.build;

import org.jspecify.annotations.Nullable;

/**
 * An expected failure with a message meant for the person running the CLI. {@link Main}
 * prints it without a stack trace; anything else is treated as a bug in the tool.
 */
public class CliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CliException(String message) {
        super(message);
    }

    public CliException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
