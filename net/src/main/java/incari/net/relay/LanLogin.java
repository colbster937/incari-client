package incari.net.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A shared world's host speaks 1.8 with Eagler's changes to its login, which Via (translating the
 * game's packets to 1.8 above this stage) doesn't know:
 *
 * <ul>
 *   <li>there's no handshake: the host starts in the login state, so the game's is dropped;
 *   <li>Login Start carries, after the name, the player's skin and cape (Eagler's formats), the
 *       Eagler protocols the client speaks, and the client's brand UUID;
 *   <li>Login Success may end with the Eagler protocol the host chose, which Via would pass on to
 *       the game as bytes it can't read, so it's cut off here.
 * </ul>
 *
 * Everything else is plain 1.8. Eagler's own messages (skins, voice) travel as plugin messages,
 * which the game ignores.
 */
// Writes go out with the channel's promises; a failed write fails the channel.
@SuppressWarnings("FutureReturnValueIgnored")
public final class LanLogin extends ChannelDuplexHandler {

    public static final String NAME = "lan-login";

    /** The Eagler protocols this client lists: 3 and 4, the ones hosts choose between. */
    private static final int[] EAGLER_PROTOCOLS = {3, 4};
    /** 1.8's login packets: serverbound Login Start and clientbound Login Success are 0 and 2. */
    private static final int LOGIN_START = 0x00;

    private static final int LOGIN_SUCCESS = 0x02;

    private final byte[] skin;
    private final byte[] cape;
    private final UUID brand;
    /** Packets the game has sent so far, while logging in: the handshake, then Login Start. */
    private int sent;

    private boolean loggedIn;

    public LanLogin(byte[] skin, byte[] cape, UUID brand) {
        this.skin = skin.clone();
        this.cape = cape.clone();
        this.brand = brand;
    }

    /** Adds the stage at the head of {@code pipeline}, above {@link LanFraming} if that's there. */
    public static void install(ChannelPipeline pipeline, LanLogin stage) {
        if (pipeline.get(LanFraming.NAME) != null) {
            pipeline.addAfter(LanFraming.NAME, NAME, stage);
        } else {
            pipeline.addFirst(NAME, stage);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (loggedIn || !(message instanceof ByteBuf packet)) {
            ctx.write(message, promise);
            return;
        }
        sent++;
        if (sent == 1) {
            // The handshake: the host has no handshake state.
            packet.release();
            promise.trySuccess();
            return;
        }
        if (sent == 2 && readVarInt(packet.duplicate()) == LOGIN_START) {
            ByteBuf out = ctx.alloc().buffer(packet.readableBytes() + skin.length + cape.length + 64);
            out.writeBytes(packet);
            packet.release();
            writeByteArray(out, skin);
            writeByteArray(out, cape);
            byte[] protocols = new byte[2 + EAGLER_PROTOCOLS.length * 2];
            protocols[1] = (byte) EAGLER_PROTOCOLS.length;
            for (int i = 0; i < EAGLER_PROTOCOLS.length; i++) {
                protocols[3 + i * 2] = (byte) EAGLER_PROTOCOLS[i];
            }
            writeByteArray(out, protocols);
            out.writeLong(brand.getMostSignificantBits());
            out.writeLong(brand.getLeastSignificantBits());
            ctx.write(out, promise);
            return;
        }
        ctx.write(packet, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (loggedIn || !(message instanceof ByteBuf packet)) {
            ctx.fireChannelRead(message);
            return;
        }
        ByteBuf peek = packet.duplicate();
        if (readVarInt(peek) != LOGIN_SUCCESS) {
            // A kick while logging in: passes as it is.
            ctx.fireChannelRead(packet);
            return;
        }
        loggedIn = true;
        String uuid = readString(peek);
        String name = readString(peek);
        // Whatever follows (the chosen Eagler protocol) is cut: Via expects the name to end it.
        ByteBuf success = ctx.alloc().buffer();
        writeVarInt(success, LOGIN_SUCCESS);
        writeString(success, uuid);
        writeString(success, name);
        packet.release();
        ctx.fireChannelRead(success);
    }

    private static void writeByteArray(ByteBuf out, byte[] bytes) {
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!in.isReadable()) {
                throw new DecoderException("Truncated VarInt");
            }
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new DecoderException("VarInt too long");
    }

    private static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static String readString(ByteBuf in) {
        int length = readVarInt(in);
        String text = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return text;
    }

    private static void writeString(ByteBuf out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }
}
