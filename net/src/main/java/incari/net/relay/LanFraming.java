package incari.net.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * How a shared world's host frames packets on the data channel: one packet per message, after a
 * byte that says what the message is. 0: a whole packet. 1: a piece of a packet, more follows. 2:
 * a whole packet, compressed, as a four-byte length and zlib data. A packet ends at a 0 or 2; the
 * pieces before it are its start. Packets this client sends go whole, or in pieces of at most
 * {@link #FRAGMENT} bytes.
 *
 * <p>This stage sits at the pipeline's head, below the message framing, so the rest sees one
 * whole packet per message, as over a WebSocket.
 */
// Writes go out with the channel's promises; a failed write fails the channel.
@SuppressWarnings("FutureReturnValueIgnored")
public final class LanFraming extends ChannelDuplexHandler {

    public static final String NAME = "lan-framing";

    /** The largest message either side sends, header included. */
    static final int FRAGMENT = 0xFF00;
    /** The largest packet a host's compressed message may unpack to. */
    private static final int MAX_PACKET = 8 * 1024 * 1024;

    private static final int WHOLE = 0;
    private static final int PIECE = 1;
    private static final int COMPRESSED = 2;

    /** The pieces of the packet being received. */
    private final ByteBuf pieces = Unpooled.buffer();

    /** Adds the stage at the head of {@code pipeline}. */
    public static void install(ChannelPipeline pipeline) {
        pipeline.addFirst(NAME, new LanFraming());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        pieces.release();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf in)) {
            ctx.fireChannelRead(message);
            return;
        }
        try {
            if (!in.isReadable()) {
                return;
            }
            int kind = in.readUnsignedByte();
            switch (kind) {
                case PIECE -> pieces.writeBytes(in);
                case WHOLE -> {
                    ByteBuf packet = ctx.alloc().buffer(pieces.readableBytes() + in.readableBytes());
                    packet.writeBytes(pieces).writeBytes(in);
                    pieces.clear();
                    ctx.fireChannelRead(packet);
                }
                case COMPRESSED -> {
                    pieces.writeBytes(in);
                    ByteBuf packet = inflate(ctx, pieces);
                    pieces.clear();
                    ctx.fireChannelRead(packet);
                }
                default -> throw new DecoderException("Unknown shared-world message kind " + kind);
            }
        } finally {
            in.release();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (!(message instanceof ByteBuf packet)) {
            ctx.write(message, promise);
            return;
        }
        try {
            int max = FRAGMENT - 1;
            if (packet.readableBytes() <= max) {
                ByteBuf out = ctx.alloc().buffer(packet.readableBytes() + 1);
                out.writeByte(WHOLE).writeBytes(packet);
                ctx.write(out, promise);
                return;
            }
            while (packet.readableBytes() > 0) {
                int length = Math.min(max, packet.readableBytes());
                ByteBuf out = ctx.alloc().buffer(length + 1);
                out.writeByte(packet.readableBytes() > length ? PIECE : WHOLE);
                out.writeBytes(packet, length);
                boolean last = !packet.isReadable();
                ctx.write(out, last ? promise : ctx.voidPromise());
            }
        } finally {
            packet.release();
        }
    }

    private static ByteBuf inflate(ChannelHandlerContext ctx, ByteBuf data) {
        int size = data.readInt();
        if (size < 0 || size > MAX_PACKET) {
            throw new DecoderException("A shared-world packet of " + size + " bytes");
        }
        byte[] compressed = new byte[data.readableBytes()];
        data.readBytes(compressed);
        byte[] packet = new byte[size];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int done = 0;
            while (done < size && !inflater.finished()) {
                int n = inflater.inflate(packet, done, size - done);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                done += n;
            }
            return ctx.alloc().buffer(done).writeBytes(packet, 0, done);
        } catch (DataFormatException e) {
            throw new DecoderException("A shared-world packet that doesn't inflate", e);
        } finally {
            inflater.end();
        }
    }
}
