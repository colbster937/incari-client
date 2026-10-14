package incari.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Packet framing for a WebSocket: one packet per binary message, as Eagler servers expect. Over
 * TCP the game prefixes each packet with its length (a VarInt) and splits the byte stream on those
 * lengths; a WebSocket already delivers whole messages, so both steps go away.
 *
 * <p>{@link #install} swaps the stream framing handlers for these, keeping their names. The game
 * places later handlers (compression, encryption) relative to those names, so they keep working,
 * and so will the Eagler handshake and the Via bridge when they join the pipeline.
 */
public final class MessageFraming {

    /** The largest packet the game's own framing allows: a length that fits a 21-bit VarInt. */
    public static final int MAX_PACKET_BYTES = (1 << 21) - 1;

    private MessageFraming() {}

    /**
     * Replaces the handlers named {@code splitterName} (the length-prefix decoder) and {@code
     * prependerName} (the length-prefix encoder) with message framing, under the same names.
     *
     * @param received told each packet's size in bytes, for the game's bandwidth chart; may be null
     */
    public static void install(
            ChannelPipeline pipeline, String splitterName, String prependerName, @Nullable IntConsumer received) {
        pipeline.replace(splitterName, splitterName, new Decoder(received));
        pipeline.replace(prependerName, prependerName, new Encoder());
    }

    /** Passes each message on as one packet, after checking its size. */
    static final class Decoder extends ChannelInboundHandlerAdapter {

        private final @Nullable IntConsumer received;

        Decoder(@Nullable IntConsumer received) {
            this.received = received;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof ByteBuf packet) {
                int size = packet.readableBytes();
                if (size == 0) {
                    packet.release();
                    throw new CorruptedFrameException("Empty WebSocket message");
                }
                if (size > MAX_PACKET_BYTES) {
                    packet.release();
                    throw new TooLongFrameException("A packet of " + size + " bytes is over the game's limit");
                }
                if (received != null) {
                    received.accept(size);
                }
            }
            ctx.fireChannelRead(message);
        }
    }

    /**
     * Writes each packet as it is: the channel sends every buffer as one message. It exists so the
     * handlers the game places before or after the prepender find it.
     */
    @ChannelHandler.Sharable
    static final class Encoder extends ChannelOutboundHandlerAdapter {}
}
