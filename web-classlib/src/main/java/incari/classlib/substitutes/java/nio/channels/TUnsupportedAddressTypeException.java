package incari.classlib.substitutes.java.nio.channels;

import java.io.Serial;

/**
 * {@code java.nio.channels.UnsupportedAddressTypeException}, which TeaVM lacks: a channel given a
 * kind of socket address it can't use. Netty's address resolvers throw it.
 */
public class TUnsupportedAddressTypeException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = -2964323842829700493L;

    public TUnsupportedAddressTypeException() {}
}
