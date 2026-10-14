package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;

/**
 * The few Minecraft and Eagler wire types this package reads and writes: VarInts, the game's
 * length-prefixed UTF-8 strings, and Eagler's byte- or short-prefixed ASCII strings.
 */
final class Wire {

    private Wire() {}

    static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte next = in.readByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new DecoderException("VarInt too long");
    }

    /** The first VarInt of a packet (its id) without moving the reader, or -1 if it's cut short. */
    static int peekVarInt(ByteBuf in) {
        int value = 0;
        int index = in.readerIndex();
        for (int shift = 0; shift < 35 && index < in.writerIndex(); shift += 7) {
            byte next = in.getByte(index++);
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    /** A game string: VarInt byte length, then UTF-8. */
    static void writeString(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    static String readString(ByteBuf in) {
        int length = readVarInt(in);
        if (length < 0 || length > in.readableBytes()) {
            throw new DecoderException("Bad string length " + length);
        }
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }

    /** An Eagler handshake string: an unsigned byte length, then ASCII, cut to 255 characters. */
    static void writeByteAscii(ByteBuf out, String value) {
        byte[] bytes = ascii(value, 255);
        out.writeByte(bytes.length);
        out.writeBytes(bytes);
    }

    static String readByteAscii(ByteBuf in) {
        int length = in.readUnsignedByte();
        String value = in.toString(in.readerIndex(), length, StandardCharsets.US_ASCII);
        in.skipBytes(length);
        return value;
    }

    /** An unsigned short length, then UTF-8 (Eagler's error and deny messages). */
    static String readShortUtf8(ByteBuf in) {
        int length = in.readUnsignedShort();
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }

    static byte[] ascii(String value, int maxLength) {
        int length = Math.min(value.length(), maxLength);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            bytes[i] = (byte) (c < 0x80 ? c : '?');
        }
        return bytes;
    }
}
