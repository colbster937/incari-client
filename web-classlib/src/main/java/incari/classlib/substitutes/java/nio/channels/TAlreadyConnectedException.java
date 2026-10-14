package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;

/**
 * {@code java.nio.channels.AlreadyConnectedException}, which TeaVM lacks: a channel that's already connected was asked to connect again. Netty's in-memory
 * channel (singleplayer's connection) throws it.
 */
public class TAlreadyConnectedException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = -7331895245053773357L;

    public TAlreadyConnectedException() {}
}
