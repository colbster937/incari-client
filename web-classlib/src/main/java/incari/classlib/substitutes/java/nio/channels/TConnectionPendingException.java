package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;

/**
 * {@code java.nio.channels.ConnectionPendingException}, which TeaVM lacks: a channel was asked to connect while a connection was already being opened. Netty's in-memory
 * channel (singleplayer's connection) throws it.
 */
public class TConnectionPendingException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 2008393366501760879L;

    public TConnectionPendingException() {}
}
