package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;

/**
 * {@code java.nio.channels.OverlappingFileLockException}, which TeaVM lacks: the requested lock
 * overlaps one this program already holds on the same file.
 */
public class TOverlappingFileLockException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 2047812138163068433L;

    public TOverlappingFileLockException() {}
}
