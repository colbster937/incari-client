package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;

/**
 * {@code java.nio.channels.ClosedByInterruptException}, which TeaVM lacks: the thread doing an I/O
 * operation was interrupted, so the channel was closed.
 */
public class TClosedByInterruptException extends TAsynchronousCloseException {

    @Serial
    private static final long serialVersionUID = -4488191543534286750L;

    public TClosedByInterruptException() {}
}
