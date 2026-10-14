package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;

/**
 * {@code java.nio.channels.NotYetConnectedException}, which TeaVM lacks: an I/O operation on a
 * channel that isn't connected yet. Netty's channels throw it.
 */
public class TNotYetConnectedException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 4697316551909513464L;

    public TNotYetConnectedException() {}
}
