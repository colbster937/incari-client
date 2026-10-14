package incari.classlib.substitutes.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * {@code java.nio.channels.ScatteringByteChannel}, which TeaVM lacks: a channel that reads into a
 * sequence of buffers in one operation.
 */
public interface TScatteringByteChannel extends ReadableByteChannel {

    long read(ByteBuffer[] dsts, int offset, int length) throws IOException;

    long read(ByteBuffer[] dsts) throws IOException;
}
