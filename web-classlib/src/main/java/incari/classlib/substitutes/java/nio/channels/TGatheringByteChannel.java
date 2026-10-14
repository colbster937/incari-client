package incari.classlib.substitutes.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * {@code java.nio.channels.GatheringByteChannel}, which TeaVM lacks: a channel that writes a
 * sequence of buffers in one operation.
 */
public interface TGatheringByteChannel extends WritableByteChannel {

    long write(ByteBuffer[] srcs, int offset, int length) throws IOException;

    long write(ByteBuffer[] srcs) throws IOException;
}
