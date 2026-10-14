package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Eagler's own messages in protocol V5: WebSocket messages that start with {@code 0xEE}, then the
 * message id and its body, travelling beside the game's packets (no packet id starts with that
 * byte: it would be a VarInt over 127). {@code 0xEE 0xFF} bundles several: a VarInt count, then
 * each message as a VarInt length and its id and body (no prefix of its own).
 *
 * <p>Sends go straight to the socket from the Eagler stage, past the game's pipeline.
 */
public final class EaglerMessages {

    static final int PREFIX = 0xEE;
    static final int BUNDLE = 0xFF;

    // Client to server (V5).
    public static final int C_GET_OTHER_SKIN = 0x01;
    public static final int C_GET_OTHER_CAPE = 0x02;
    public static final int C_GET_OTHER_TEXTURES = 0x03;
    public static final int C_VOICE_REQUEST = 0x06;
    public static final int C_VOICE_CONNECT = 0x07;
    public static final int C_VOICE_DISCONNECT = 0x08;
    public static final int C_VOICE_DISCONNECT_PEER = 0x09;
    public static final int C_VOICE_ICE = 0x0A;
    public static final int C_VOICE_DESCRIPTION = 0x0B;

    // Server to client (V5).
    public static final int S_OTHER_SKIN_PRESET = 0x01;
    public static final int S_OTHER_SKIN_CUSTOM = 0x02;
    public static final int S_OTHER_CAPE_PRESET = 0x03;
    public static final int S_OTHER_CAPE_CUSTOM = 0x04;
    public static final int S_OTHER_TEXTURES = 0x05;
    public static final int S_VOICE_ALLOWED = 0x06;
    public static final int S_VOICE_CONNECT = 0x07;
    public static final int S_VOICE_CONNECT_ANNOUNCE = 0x08;
    public static final int S_VOICE_DISCONNECT_PEER = 0x09;
    public static final int S_VOICE_ICE = 0x0A;
    public static final int S_VOICE_DESCRIPTION = 0x0B;
    public static final int S_VOICE_GLOBAL = 0x0C;
    public static final int S_FORCE_SKIN_PRESET = 0x0F;
    public static final int S_FORCE_SKIN_CUSTOM = 0x10;
    public static final int S_FORCE_CAPE_PRESET = 0x14;
    public static final int S_FORCE_CAPE_CUSTOM = 0x15;
    public static final int S_SET_COOKIE = 0x11;
    public static final int S_REDIRECT = 0x12;
    public static final int S_INVALIDATE_PLAYER = 0x16;
    public static final int S_CUSTOMIZE_PAUSE_MENU = 0x18;

    /** Where messages go: the socket, or a test's list. */
    interface Sink {
        ByteBuf allocate();

        void write(ByteBuf message);
    }

    private final Sink sink;

    EaglerMessages(Sink sink) {
        this.sink = sink;
    }

    /** Messages written from the Eagler stage's context, straight to the socket. */
    // Written with the void promise: a failed write fails the channel, which the game reports.
    @SuppressWarnings("FutureReturnValueIgnored")
    static EaglerMessages on(ChannelHandlerContext context) {
        return new EaglerMessages(new Sink() {
            @Override
            public ByteBuf allocate() {
                return context.alloc().buffer();
            }

            @Override
            public void write(ByteBuf message) {
                Channel channel = context.channel();
                if (!channel.isActive()) {
                    message.release();
                } else if (context.executor().inEventLoop()) {
                    context.writeAndFlush(message, context.voidPromise());
                } else {
                    context.executor().execute(() -> context.writeAndFlush(message, context.voidPromise()));
                }
            }
        });
    }

    /** Sends message {@code id} with the body {@code body} writes. Any thread. */
    public void send(int id, Consumer<ByteBuf> body) {
        ByteBuf message = sink.allocate();
        message.writeByte(PREFIX);
        message.writeByte(id);
        body.accept(message);
        sink.write(message);
    }

    /** Writes a VarInt, for message bodies. */
    public static void writeVarInt(ByteBuf out, int value) {
        Wire.writeVarInt(out, value);
    }

    /** Reads a VarInt, for message bodies. */
    public static int readVarInt(ByteBuf in) {
        return Wire.readVarInt(in);
    }

    /** Reads a VarLong (a cookie's lifetime). */
    public static long readVarLong(ByteBuf in) {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new DecoderException("VarLong too long");
    }

    /** Reads a game string: VarInt length, UTF-8 (voice servers' addresses, player names). */
    public static String readString(ByteBuf in) {
        return Wire.readString(in);
    }

    /** Reads a short-length ASCII string (a redirect's address). */
    public static String readShortAscii(ByteBuf in) {
        int length = in.readUnsignedShort();
        String value = in.toString(in.readerIndex(), length, StandardCharsets.US_ASCII);
        in.skipBytes(length);
        return value;
    }

    /**
     * Splits a {@code 0xEE} message (the prefix already read) into its messages and hands each to
     * {@code session}.
     */
    static void dispatch(ByteBuf message, EaglerSession session) {
        if (!message.isReadable()) {
            return;
        }
        if (message.getUnsignedByte(message.readerIndex()) != BUNDLE) {
            int id = message.readUnsignedByte();
            session.received(id, message);
            return;
        }
        message.skipBytes(1);
        int count = Wire.readVarInt(message);
        for (int i = 0; i < count && message.isReadable(); i++) {
            ByteBuf entry = message.readSlice(Wire.readVarInt(message));
            if (entry.isReadable()) {
                session.received(entry.readUnsignedByte(), entry);
            }
        }
    }
}
