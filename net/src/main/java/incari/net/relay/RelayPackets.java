package incari.net.relay;

import incari.platform.api.rtc.IceServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The messages of Eagler's shared-world relay (protocol version 1): one binary WebSocket message
 * each, an id byte, then big-endian fields. Strings are ASCII with a one- or two-byte length.
 * Written from the protocol; only the ones a joining client sends or reads are here.
 */
final class RelayPackets {

    static final int HANDSHAKE = 0x00;
    static final int ICE_SERVERS = 0x01;
    static final int NEW_CLIENT = 0x02;
    static final int ICE_CANDIDATE = 0x03;
    static final int DESCRIPTION = 0x04;
    static final int CLIENT_SUCCESS = 0x05;
    static final int CLIENT_FAILURE = 0x06;
    static final int PONG = 0x69;
    static final int SPECIAL_UPDATE = 0x70;
    static final int DISCONNECT_CLIENT = 0xFE;
    static final int ERROR = 0xFF;

    /** The relay protocol this client speaks. */
    static final int VERSION = 1;
    /** A handshake's connection type: a client joining a world by its code. */
    static final int TYPE_CLIENT = 0x02;
    /** A handshake's connection type: a host sharing a world, which the relay gives a code. */
    static final int TYPE_SERVER = 0x01;

    private RelayPackets() {}

    static ByteBuffer handshake(String code) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(HANDSHAKE);
        out.writeByte(TYPE_CLIENT);
        out.writeByte(VERSION);
        writeAscii8(out, code);
        return nio(out);
    }

    /**
     * A host's hello (ADR 0062): the world's name and whether it's hidden from the relay's world
     * list, as "name;0" or "name;1", as Eagler's hosts write it.
     */
    static ByteBuffer hostHandshake(String worldName, boolean hidden) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(HANDSHAKE);
        out.writeByte(TYPE_SERVER);
        out.writeByte(VERSION);
        String name = worldName.replace(';', ' ');
        // The relay reads it as one ASCII string of at most 255 bytes.
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < name.length() && ascii.length() < 200; i++) {
            char c = name.charAt(i);
            ascii.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        writeAscii8(out, ascii + (hidden ? ";1" : ";0"));
        return nio(out);
    }

    /** A host's answer to the client {@code peerId}. */
    static ByteBuffer description(String peerId, String json) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(DESCRIPTION);
        writeAscii8(out, peerId);
        writeBytes16(out, json.getBytes(StandardCharsets.ISO_8859_1));
        return nio(out);
    }

    /** A host's ICE candidates for the client {@code peerId}, all at once. */
    static ByteBuffer iceCandidates(String peerId, String jsonArray) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(ICE_CANDIDATE);
        writeAscii8(out, peerId);
        writeBytes16(out, jsonArray.getBytes(StandardCharsets.ISO_8859_1));
        return nio(out);
    }

    /** This client's offer; the host is the only peer, so the peer id is empty. */
    static ByteBuffer description(String json) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(DESCRIPTION);
        writeAscii8(out, "");
        writeBytes16(out, json.getBytes(StandardCharsets.ISO_8859_1));
        return nio(out);
    }

    /** This client's ICE candidates, all at once, as a JSON array. */
    static ByteBuffer iceCandidates(String jsonArray) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(ICE_CANDIDATE);
        writeAscii8(out, "");
        writeBytes16(out, jsonArray.getBytes(StandardCharsets.ISO_8859_1));
        return nio(out);
    }

    static ByteBuffer clientSuccess(String peerId) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(CLIENT_SUCCESS);
        writeAscii8(out, peerId);
        return nio(out);
    }

    static ByteBuffer clientFailure(String peerId) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(CLIENT_FAILURE);
        writeAscii8(out, peerId);
        return nio(out);
    }

    /** A message from the relay, read. */
    sealed interface Message {}

    record Handshake(int type, int version, String code) implements Message {}

    record IceServers(List<IceServer> servers) implements Message {}

    /** The host's candidates ({@link #ICE_CANDIDATE}) or description ({@link #DESCRIPTION}). */
    record Signal(int id, String peerId, String text) implements Message {}

    record Refusal(int code, String message) implements Message {}

    /** To a host: a client asks to join ({@link #NEW_CLIENT}). */
    record NewClient(String clientId) implements Message {}

    /** To a host: a client says its connection worked or not ({@link #CLIENT_SUCCESS}, {@link #CLIENT_FAILURE}). */
    record ClientDone(String clientId, boolean success) implements Message {}

    /** Anything this client has no use for (pongs, update certificates). */
    record Ignored(int id) implements Message {}

    static Message read(ByteBuf in) {
        int id = in.readUnsignedByte();
        return switch (id) {
            case HANDSHAKE -> new Handshake(in.readUnsignedByte(), in.readUnsignedByte(), readAscii8(in));
            case ICE_SERVERS -> {
                int count = in.readUnsignedShort();
                List<IceServer> servers = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int type = in.readUnsignedByte();
                    String url = readAscii16(in);
                    String user = readAscii8(in);
                    String password = readAscii8(in);
                    if (type == 'T') {
                        servers.add(new IceServer(url, user, password));
                    } else if (type == 'S') {
                        servers.add(new IceServer(url, null, null));
                    } else {
                        throw new DecoderException("Unknown relay ICE server type " + type);
                    }
                }
                yield new IceServers(servers);
            }
            case ICE_CANDIDATE, DESCRIPTION -> {
                String peer = readAscii8(in);
                int length = in.readUnsignedShort();
                String text = in.toString(in.readerIndex(), length, StandardCharsets.ISO_8859_1);
                in.skipBytes(length);
                yield new Signal(id, peer, text);
            }
            case ERROR -> new Refusal(in.readUnsignedByte(), readAscii16(in));
            case NEW_CLIENT -> new NewClient(readAscii8(in));
            case CLIENT_SUCCESS, CLIENT_FAILURE -> new ClientDone(readAscii8(in), id == CLIENT_SUCCESS);
            default -> new Ignored(id);
        };
    }

    private static void writeAscii8(ByteBuf out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.writeByte(bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeBytes16(ByteBuf out, byte[] bytes) {
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }

    private static String readAscii8(ByteBuf in) {
        int length = in.readUnsignedByte();
        String text = in.toString(in.readerIndex(), length, StandardCharsets.ISO_8859_1);
        in.skipBytes(length);
        return text;
    }

    private static String readAscii16(ByteBuf in) {
        int length = in.readUnsignedShort();
        String text = in.toString(in.readerIndex(), length, StandardCharsets.ISO_8859_1);
        in.skipBytes(length);
        return text;
    }

    private static ByteBuffer nio(ByteBuf out) {
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        out.release();
        return ByteBuffer.wrap(bytes);
    }
}
