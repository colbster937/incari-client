package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;
import java.nio.channels.ClosedChannelException;

/**
 * {@code java.nio.channels.AsynchronousCloseException}, which TeaVM lacks: an I/O operation ended
 * because another thread closed the channel.
 */
public class TAsynchronousCloseException extends ClosedChannelException {

    @Serial
    private static final long serialVersionUID = 6891178312432313966L;

    public TAsynchronousCloseException() {}
}
