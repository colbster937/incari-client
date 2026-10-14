package incari.net.relay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * What an Incari host says first on a guest's data channel (ADR 0062), so an Incari guest knows the
 * world is today's game and joins it as it is, without Via and Eagler's 1.8 login. Eagler's hosts
 * never speak first: their guest does. One message: a kind byte none of {@link LanFraming}'s
 * (0x49), "INCARI", then the game's protocol number as four bytes, big-endian.
 */
public final class HostHello {

    private static final byte KIND = 0x49;
    private static final byte[] MAGIC = "INCARI".getBytes(StandardCharsets.US_ASCII);
    private static final int LENGTH = 1 + 6 + 4;

    /** How long a guest waits for it after the channel opens, before taking the host for Eagler's. */
    public static final long WAIT_MILLIS = 1_500;

    private HostHello() {}

    /** The hello for a host playing the game's {@code protocol}. */
    public static ByteBuffer write(int protocol) {
        ByteBuffer out = ByteBuffer.allocate(LENGTH);
        out.put(KIND).put(MAGIC).putInt(protocol).flip();
        return out;
    }

    /** The protocol {@code message} names, if it's a hello; -1 if it isn't one. */
    public static int read(ByteBuffer message) {
        ByteBuffer in = message.duplicate();
        if (in.remaining() != LENGTH || in.get() != KIND) {
            return -1;
        }
        for (byte b : MAGIC) {
            if (in.get() != b) {
                return -1;
            }
        }
        return in.getInt();
    }
}
