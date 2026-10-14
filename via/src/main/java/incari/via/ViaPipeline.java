package incari.via;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.exception.CancelCodecException;
import com.viaversion.viaversion.exception.CancelDecoderException;
import com.viaversion.viaversion.exception.CancelEncoderException;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import org.jspecify.annotations.Nullable;

/**
 * Via as a stage of one connection's pipeline: packets from the server are translated to the
 * game's protocol after framing (and decompression), and the game's packets to the server's
 * protocol before it. Via sees whole, uncompressed packets both ways, as it does on a proxy.
 *
 * <p>The stage sits right after the framing handlers the game names "splitter" and "prepender":
 * the game adds its compression next to those names too, so decompression lands before Via's
 * decoder and compression after its encoder, whenever the server turns it on.
 */
public final class ViaPipeline {

    /** The stage's handler names. */
    public static final String DECODER = "via-decoder";

    public static final String ENCODER = "via-encoder";

    public static final String RELAY = "via-relay";

    private ViaPipeline() {}

    /**
     * Adds Via to {@code channel}'s pipeline for a server speaking {@code serverProtocol}, after the
     * handlers named {@code splitter} and {@code prepender}. {@link IncariVia#load} must have run.
     */
    public static void install(Channel channel, int serverProtocol, String splitter, String prepender) {
        ProtocolVersion server = ProtocolVersion.getProtocol(VersionType.RELEASE, serverProtocol);
        UserConnection user = new UserConnectionImpl(channel, true);
        new ProtocolPipelineImpl(user);
        user.put(new ServerVersions.Target(server));
        Relay relay = new Relay();
        channel.pipeline().addAfter(splitter, DECODER, new Decoder(user));
        channel.pipeline().addAfter(DECODER, RELAY, relay);
        channel.pipeline().addAfter(prepender, ENCODER, new Encoder(user, relay));
    }

    /** Whether {@code failure} is Via dropping a packet on purpose. */
    static boolean cancelled(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancelCodecException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translates each packet from the server. Not Netty's {@code MessageToMessageDecoder}: it
     * catches and rethrows each packet Via drops, three throws on top of Via's own, and in the page a
     * throw off the main thread costs ten times one on it (ADR 0033). A 1.12 server dropped a dozen
     * packets a second. Like Netty's, this releases the input, passes the output on and wraps a
     * failure as a {@code DecoderException}.
     */
    private static final class Decoder extends ChannelInboundHandlerAdapter {

        private final UserConnection user;
        private boolean decodeCalled;
        private boolean messageProduced;

        Decoder(UserConnection user) {
            this.user = user;
        }

        /** The packet translated for the game, or null if Via drops it. */
        private @Nullable ByteBuf decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            if (!user.checkIncomingPacket(in.readableBytes())) {
                return null;
            }
            if (!user.shouldTransformPacket()) {
                return in.retain();
            }
            ByteBuf transformed = ctx.alloc().buffer(in.readableBytes()).writeBytes(in);
            // Caught, not a finally: a finally rethrows, one more throw per dropped packet.
            try {
                user.transformIncoming(transformed, CancelDecoderException::generate);
            } catch (Throwable failure) {
                transformed.release();
                if (cancelled(failure)) {
                    return null;
                }
                throw failure;
            }
            return transformed;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            decodeCalled = true;
            if (!(message instanceof ByteBuf in)) {
                messageProduced = true;
                ctx.fireChannelRead(message);
                return;
            }
            ByteBuf out;
            try {
                out = decode(ctx, in);
            } catch (Exception e) {
                // Via drops some packets on purpose (ones the game's version has no use for).
                if (cancelled(e)) {
                    return;
                }
                throw e instanceof DecoderException d ? d : new DecoderException(e);
            } finally {
                in.release();
            }
            if (out != null) {
                messageProduced = true;
                ctx.fireChannelRead(out);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            // As MessageToMessageDecoder: ask for more if a read produced nothing without auto-read.
            if (decodeCalled && !messageProduced && !ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
            decodeCalled = false;
            messageProduced = false;
            ctx.fireChannelReadComplete();
        }
    }

    /**
     * Translates each packet the game sends. Not Netty's {@code MessageToMessageEncoder}, as with
     * the decoder: there a dropped packet costs four more throws, and the game sends one every tick
     * that an old server has no equivalent for. Like Netty's, this releases the input, writes the
     * output with the game's promise (a dropped packet completes it) and wraps a failure as an
     * {@code EncoderException}.
     */
    private static final class Encoder extends ChannelOutboundHandlerAdapter {

        private final UserConnection user;
        private final Relay relay;

        Encoder(UserConnection user, Relay relay) {
            this.user = user;
            this.relay = relay;
        }

        /** The packet translated for the server, or null if Via drops it. */
        private @Nullable ByteBuf encode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            if (!user.checkOutgoingPacket()) {
                return null;
            }
            if (!user.shouldTransformPacket()) {
                return in.retain();
            }
            ByteBuf transformed = ctx.alloc().buffer(in.readableBytes()).writeBytes(in);
            relay.encoding = true;
            // Caught, not a finally: a finally rethrows, one more throw per dropped packet.
            try {
                user.transformOutgoing(transformed, CancelEncoderException::generate);
            } catch (Throwable failure) {
                relay.encoding = false;
                transformed.release();
                if (cancelled(failure)) {
                    return null;
                }
                throw failure;
            }
            relay.encoding = false;
            return transformed;
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored") // the game's promise carries the write's outcome
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (!(message instanceof ByteBuf in)) {
                ctx.write(message, promise);
                return;
            }
            ByteBuf out;
            try {
                out = encode(ctx, in);
            } catch (Exception e) {
                if (!cancelled(e)) {
                    throw e instanceof EncoderException d ? d : new EncoderException(e);
                }
                out = null;
            } finally {
                in.release();
            }
            if (out == null) {
                // A packet the server's version has no equivalent for: it's done, as far as the game knows.
                promise.trySuccess();
                return;
            }
            ctx.write(out, promise);
        }
    }

    /**
     * Holds back packets Via hands the game while translating one of the game's own. Via answers
     * some packets for the server: when the game finishes the configuration phase (which an old
     * server lacks), Via releases the server's queued play packets at once. The game only switches
     * protocol after that write returns, so they go on as the next task on the connection's
     * thread, in order.
     */
    private static final class Relay extends ChannelInboundHandlerAdapter {

        /** Set while the encoder translates; the connection's thread only. */
        boolean encoding;

        private final ArrayDeque<Object> held = new ArrayDeque<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!encoding && held.isEmpty()) {
                ctx.fireChannelRead(message);
                return;
            }
            if (held.isEmpty()) {
                ctx.executor().execute(() -> release(ctx));
            }
            held.add(message);
        }

        private void release(ChannelHandlerContext ctx) {
            while (!held.isEmpty()) {
                ctx.fireChannelRead(held.remove());
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            while (!held.isEmpty()) {
                ReferenceCountUtil.release(held.remove());
            }
        }
    }
}
