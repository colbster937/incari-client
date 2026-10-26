package incari.net.wisp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * A server's {@code _minecraft._tcp} SRV record, asked of a public resolver over DNS-over-TCP through
 * the Wisp proxy itself: a page can't look names up, and the proxy only opens sockets. The game
 * does the same lookup for a host given without a port.
 */
final class SrvLookup {

    /** Cloudflare's resolver, as Wispcraft uses (it asks over HTTPS; TCP needs no TLS here). */
    static final String RESOLVER_HOST = "1.1.1.1";

    static final int RESOLVER_PORT = 53;

    private static final int TYPE_SRV = 33;
    private static final int CLASS_IN = 1;
    private static final int MAX_LABEL = 63;
    /** Compression pointers followed per name; a loop in a bad reply stops here. */
    private static final int MAX_JUMPS = 16;

    /** Where an SRV record points. */
    record Target(String host, int port) {}

    private SrvLookup() {}

    /** The record's name for {@code host}, or null if {@code host} can't be one (a label too long). */
    static @Nullable String recordName(String host) {
        String name = "_minecraft._tcp." + host;
        for (String label : name.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                return null;
            }
        }
        return name;
    }

    /** A query for {@code name}'s SRV records, framed for TCP (a two-byte length first). */
    static byte[] query(String name, int id) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        writeShort(message, id);
        writeShort(message, 0x0100); // a standard query, recursion desired
        writeShort(message, 1); // one question
        writeShort(message, 0);
        writeShort(message, 0);
        writeShort(message, 0);
        for (String label : name.split("\\.", -1)) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            message.write(bytes.length);
            message.write(bytes, 0, bytes.length);
        }
        message.write(0);
        writeShort(message, TYPE_SRV);
        writeShort(message, CLASS_IN);
        byte[] body = message.toByteArray();
        ByteBuffer framed = ByteBuffer.allocate(2 + body.length);
        framed.putShort((short) body.length).put(body);
        return framed.array();
    }

    /**
     * The length of the reply framed at the start of {@code received}, or -1 if its two-byte length
     * hasn't all arrived.
     */
    static int replyLength(byte[] received, int count) {
        return count < 2 ? -1 : ((received[0] & 0xFF) << 8) | (received[1] & 0xFF);
    }

    /**
     * The best target in a reply to query {@code id} (lowest priority, then highest weight), or null
     * if there's none or the reply can't be read.
     */
    static @Nullable Target parse(byte[] reply, int id) {
        try {
            ByteBuffer in = ByteBuffer.wrap(reply);
            if ((in.getShort() & 0xFFFF) != id) {
                return null;
            }
            int flags = in.getShort() & 0xFFFF;
            if ((flags & 0x8000) == 0 || (flags & 0x000F) != 0) {
                return null; // not a reply, or an error (no such name, refused)
            }
            int questions = in.getShort() & 0xFFFF;
            int answers = in.getShort() & 0xFFFF;
            in.getShort();
            in.getShort();
            for (int i = 0; i < questions; i++) {
                readName(reply, in);
                in.position(in.position() + 4);
            }
            Target best = null;
            int bestPriority = Integer.MAX_VALUE;
            int bestWeight = -1;
            for (int i = 0; i < answers; i++) {
                readName(reply, in);
                int type = in.getShort() & 0xFFFF;
                in.getShort();
                in.getInt();
                int length = in.getShort() & 0xFFFF;
                int end = in.position() + length;
                if (type == TYPE_SRV) {
                    int priority = in.getShort() & 0xFFFF;
                    int weight = in.getShort() & 0xFFFF;
                    int port = in.getShort() & 0xFFFF;
                    String host = readName(reply, in);
                    // "." names no service; port 0 can't be joined.
                    boolean better = priority < bestPriority || (priority == bestPriority && weight > bestWeight);
                    if (!host.isEmpty() && port != 0 && better) {
                        best = new Target(host, port);
                        bestPriority = priority;
                        bestWeight = weight;
                    }
                }
                in.position(end);
            }
            return best;
        } catch (RuntimeException e) {
            // A truncated or malformed reply: join the address as written.
            return null;
        }
    }

    /** Reads a name at {@code in}'s position, following compression pointers into {@code reply}. */
    private static String readName(byte[] reply, ByteBuffer in) {
        StringBuilder name = new StringBuilder();
        int at = in.position();
        int resume = -1;
        for (int jumps = 0; ; ) {
            int length = reply[at] & 0xFF;
            if (length == 0) {
                at++;
                break;
            }
            if ((length & 0xC0) == 0xC0) {
                if (++jumps > MAX_JUMPS) {
                    throw new IllegalArgumentException("Name compression loops");
                }
                if (resume < 0) {
                    resume = at + 2;
                }
                at = ((length & 0x3F) << 8) | (reply[at + 1] & 0xFF);
                continue;
            }
            if (!name.isEmpty()) {
                name.append('.');
            }
            name.append(new String(reply, at + 1, length, StandardCharsets.US_ASCII));
            at += 1 + length;
        }
        in.position(resume >= 0 ? resume : at);
        return name.toString();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }
}
