package incari.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageFramingTest {

    private final List<Integer> sizes = new ArrayList<>();

    /** A pipeline shaped like the game's: stream framing under the names the game uses. */
    private EmbeddedChannel gameShapedChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline()
                .addLast("splitter", new ChannelInboundHandlerAdapter())
                .addLast("decoder", new ChannelInboundHandlerAdapter())
                .addLast("prepender", new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        throw new AssertionError("the stream prepender must be gone");
                    }
                })
                .addLast("encoder", new ChannelOutboundHandlerAdapter());
        MessageFraming.install(channel.pipeline(), "splitter", "prepender", sizes::add);
        return channel;
    }

    @Test
    void keepsTheHandlerNamesTheGamePlacesOthersBy() {
        EmbeddedChannel channel = gameShapedChannel();

        assertThat(channel.pipeline().names()).containsSubsequence("splitter", "decoder", "prepender", "encoder");
        assertThat(channel.pipeline().get("splitter")).isInstanceOf(MessageFraming.Decoder.class);
        assertThat(channel.pipeline().get("prepender")).isInstanceOf(MessageFraming.Encoder.class);
        // What the game does when the server enables compression.
        channel.pipeline().addAfter("splitter", "decompress", new ChannelInboundHandlerAdapter());
        channel.pipeline().addAfter("prepender", "compress", new ChannelOutboundHandlerAdapter());
    }

    @Test
    void eachMessageIsOnePacketAsItArrived() {
        EmbeddedChannel channel = gameShapedChannel();

        channel.writeInbound(text("first"), text("second packet"));

        assertThat(readText(channel.readInbound())).isEqualTo("first");
        assertThat(readText(channel.readInbound())).isEqualTo("second packet");
        assertThat(sizes).containsExactly(5, 13);
    }

    @Test
    void eachPacketIsWrittenWithoutALengthPrefix() {
        EmbeddedChannel channel = gameShapedChannel();

        channel.writeOutbound(text("packet"));

        assertThat(readText(channel.readOutbound())).isEqualTo("packet");
    }

    @Test
    void rejectsEmptyAndOversizedMessages() {
        EmbeddedChannel channel = gameShapedChannel();
        ByteBuf empty = Unpooled.buffer(0);
        ByteBuf huge =
                Unpooled.buffer(MessageFraming.MAX_PACKET_BYTES + 1).writerIndex(MessageFraming.MAX_PACKET_BYTES + 1);

        assertThatThrownBy(() -> channel.writeInbound(empty)).isInstanceOf(CorruptedFrameException.class);
        assertThatThrownBy(() -> channel.writeInbound(huge)).isInstanceOf(TooLongFrameException.class);
        assertThat(empty.refCnt()).isZero();
        assertThat(huge.refCnt()).isZero();
    }

    private static ByteBuf text(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    private static String readText(Object message) {
        ByteBuf buffer = (ByteBuf) message;
        try {
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            buffer.release();
        }
    }
}
