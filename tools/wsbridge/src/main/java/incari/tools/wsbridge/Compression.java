package incari.tools.wsbridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.DecoderException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The Java protocol's packet compression, on the bridge's link to the server: once the server
 * sets a threshold, each packet starts with its uncompressed length as a VarInt, 0 meaning "sent
 * as is"; longer ones are zlib-compressed.
 */
final class Compression {

    private Compression() {}

    static ByteBuf decompress(ByteBufAllocator alloc, ByteBuf packet) {
        int length = Packets.readVarInt(packet);
        if (length == 0) {
            return packet.retainedSlice();
        }
        byte[] input = new byte[packet.readableBytes()];
        packet.readBytes(input);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            byte[] output = new byte[length];
            int done = 0;
            while (done < length && !inflater.finished()) {
                int inflated = inflater.inflate(output, done, length - done);
                if (inflated == 0 && inflater.needsInput()) {
                    break;
                }
                done += inflated;
            }
            if (done != length) {
                throw new DecoderException("A compressed packet was " + done + " bytes, not " + length);
            }
            return alloc.buffer(length).writeBytes(output);
        } catch (DataFormatException e) {
            throw new DecoderException("A malformed compressed packet", e);
        } finally {
            inflater.end();
        }
    }

    static ByteBuf compress(ByteBufAllocator alloc, ByteBuf packet, int threshold) {
        int length = packet.readableBytes();
        ByteBuf out = alloc.buffer(length + 5);
        if (length < threshold) {
            Packets.writeVarInt(out, 0);
            out.writeBytes(packet, packet.readerIndex(), length);
            return out;
        }
        byte[] input = new byte[length];
        packet.getBytes(packet.readerIndex(), input);
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            Packets.writeVarInt(out, length);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.writeBytes(buffer, 0, deflater.deflate(buffer));
            }
            return out;
        } finally {
            deflater.end();
        }
    }
}
