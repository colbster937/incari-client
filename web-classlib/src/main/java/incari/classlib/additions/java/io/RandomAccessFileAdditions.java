package incari.classlib.additions.java.io;

import incari.classlib.InstanceMethod;
import incari.classlib.Replaces;
import incari.classlib.nio.FileChannels;
import incari.classlib.substitutes.java.nio.channels.TFileChannel;
import java.io.IOException;
import java.io.RandomAccessFile;

/** {@code java.io.RandomAccessFile} methods TeaVM lacks, or has wrong. */
public final class RandomAccessFileAdditions {

    private RandomAccessFileAdditions() {}

    /**
     * The file's channel, whose position is the file's pointer. Closing the channel closes the
     * file; closing the file is only noticed by the channel's next operation, which fails.
     */
    @InstanceMethod
    public static TFileChannel getChannel(RandomAccessFile self) {
        return FileChannels.of(self);
    }

    /**
     * The next byte, 0 to 255, or -1 at the end: TeaVM 0.15's returns the byte signed (see
     * {@code FileInputStreamAdditions.read}), so its own {@code readByte} and
     * {@code readUnsignedByte} took a byte of 0x80 or more for the end of the file.
     */
    @InstanceMethod
    @Replaces
    public static int read(RandomAccessFile self) throws IOException {
        byte[] one = new byte[1];
        return self.read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
    }
}
