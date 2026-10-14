package incari.classlib.substitutes.java.nio.channels;

import java.io.IOException;
import java.nio.channels.Channel;

/**
 * {@code java.nio.channels.InterruptibleChannel}, which TeaVM lacks: a channel that can be closed
 * from another thread, and that closes when a thread in one of its operations is interrupted.
 */
public interface TInterruptibleChannel extends Channel {

    @Override
    void close() throws IOException;
}
