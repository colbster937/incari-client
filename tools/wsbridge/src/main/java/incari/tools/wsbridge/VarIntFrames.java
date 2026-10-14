package incari.tools.wsbridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;

/**
 * The Java protocol's stream framing, as the bridge sees it: each packet is prefixed with its
 * length as a VarInt (7 bits per byte, low bits first, high bit set on all but the last byte), at
 * most three bytes long. The bridge never looks inside a packet.
 */
final class VarIntFrames {

    /** Three VarInt bytes hold 21 bits. */
    static final int MAX_FRAME = (1 << 21) - 1;

    private VarIntFrames() {}

    /** Writes {@code length} as a VarInt. */
    static void writeLength(ByteBuf out, int length) {
        int remaining = length;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }

    /** Splits a TCP byte stream into packets, emitting each without its length prefix. */
    static final class Splitter extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (true) {
                int start = in.readerIndex();
                int length = 0;
                int shift = 0;
                boolean complete = false;
                for (int i = 0; i < 3 && in.isReadable(); i++) {
                    int b = in.readUnsignedByte();
                    length |= (b & 0x7F) << shift;
                    shift += 7;
                    if ((b & 0x80) == 0) {
                        complete = true;
                        break;
                    }
                }
                if (!complete) {
                    if (in.readerIndex() - start >= 3) {
                        throw new CorruptedFrameException("A frame length longer than 21 bits");
                    }
                    in.readerIndex(start);
                    return;
                }
                if (length == 0) {
                    throw new CorruptedFrameException("A zero-length frame");
                }
                if (in.readableBytes() < length) {
                    in.readerIndex(start);
                    return;
                }
                out.add(in.readRetainedSlice(length));
            }
        }
    }
}
