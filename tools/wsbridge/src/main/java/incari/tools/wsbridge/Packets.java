package incari.tools.wsbridge;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The few wire types the bridge reads and writes while it stands in for an Eagler proxy's login. */
final class Packets {

    private Packets() {}

    static void writeVarInt(ByteBuf out, int value) {
        VarIntFrames.writeLength(out, value);
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

    static void writeString(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    static String readString(ByteBuf in) {
        int length = readVarInt(in);
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }

    static void writeUuid(ByteBuf out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    /** An Eagler handshake string: an unsigned byte length, then ASCII. */
    static String readByteAscii(ByteBuf in) {
        int length = in.readUnsignedByte();
        String value = in.toString(in.readerIndex(), length, StandardCharsets.US_ASCII);
        in.skipBytes(length);
        return value;
    }

    static void writeByteAscii(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(bytes.length);
        out.writeBytes(bytes);
    }

    static void writeShortUtf8(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }

    /** The UUID an offline-mode server gives {@code name}, as Eagler proxies compute it too. */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
