package incari.classlib.substitutes.java.nio.channels;

import java.io.IOException;
import java.io.Serial;

/**
 * {@code java.nio.channels.FileLockInterruptionException}, which TeaVM lacks. The browser build
 * never throws it: its locks are granted at once, so no thread waits for one.
 */
public class TFileLockInterruptionException extends IOException {

    @Serial
    private static final long serialVersionUID = 7104080643653532383L;

    public TFileLockInterruptionException() {}
}
