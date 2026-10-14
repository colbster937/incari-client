package incari.classlib.substitutes.java.net;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code java.net.SocketAddress}, which TeaVM lacks: the abstract type of an endpoint. Netty's
 * channels and the game's connection code pass endpoints around as this.
 */
public abstract class TSocketAddress implements Serializable {

    @Serial
    private static final long serialVersionUID = 5215720748342549866L;

    public TSocketAddress() {}
}
