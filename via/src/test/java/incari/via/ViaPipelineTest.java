package incari.via;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Via between a 26.3 game and servers of other versions, in a pipeline shaped like the game's. */
class ViaPipelineTest {

    private static final int GAME = 777;

    @TempDir
    static File folder;

    @BeforeAll
    static void configure() {
        IncariVia.configure(folder, GAME);
    }

    @Test
    void knowsTheReleasesItCanJoin() {
        assertThat(IncariVia.supports(47)).isTrue();
        assertThat(IncariVia.supports(340)).isTrue();
        assertThat(IncariVia.supports(GAME)).isTrue();
        assertThat(IncariVia.supports(5)).isFalse(); // 1.7: ViaVersion starts at 1.8
        assertThat(IncariVia.versionName(47)).isEqualTo("1.8.x");
        assertThat(IncariVia.versionName(765)).isEqualTo("1.20.3-1.20.4");
        assertThat(IncariVia.releases().get(0).protocol()).isGreaterThanOrEqualTo(GAME);
    }

    @Test
    void readsVersionsNamedInWords() {
        assertThat(IncariVia.protocolNamed("1.8.8")).isEqualTo(47);
        assertThat(IncariVia.protocolNamed("1.8.x")).isEqualTo(47);
        assertThat(IncariVia.protocolNamed("1.12.2")).isEqualTo(340);
        assertThat(IncariVia.protocolNamed("1.20.4")).isEqualTo(765);
        // The newest 1.20 release, not 1.20 itself.
        assertThat(IncariVia.protocolNamed("1.20.x")).isGreaterThan(765);
        assertThat(IncariVia.protocolNamed("1.7.10")).isEqualTo(-1);
        assertThat(IncariVia.protocolNamed("banana")).isEqualTo(-1);
    }

    @Test
    void takesTheGameToA18Server() {
        IncariVia.load(47);
        List<ByteBuf> game = new ArrayList<>();
        EmbeddedChannel channel = channel(game);
        ViaPipeline.install(channel, 47, "splitter", "prepender");

        UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
        ByteBuf handshake = Unpooled.buffer();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, GAME);
        writeString(handshake, "localhost");
        handshake.writeShort(25565);
        writeVarInt(handshake, 2);
        ByteBuf hello = Unpooled.buffer();
        writeVarInt(hello, 0x00);
        writeString(hello, "Steve");
        hello.writeLong(uuid.getMostSignificantBits());
        hello.writeLong(uuid.getLeastSignificantBits());
        channel.writeOutbound(handshake, hello);

        ByteBuf sentHandshake = channel.readOutbound();
        assertThat(readVarInt(sentHandshake)).isEqualTo(0x00);
        assertThat(readVarInt(sentHandshake)).isEqualTo(47);
        ByteBuf sentHello = channel.readOutbound();
        assertThat(readVarInt(sentHello)).isEqualTo(0x00);
        assertThat(readString(sentHello)).isEqualTo("Steve");
        assertThat(sentHello.isReadable()).isFalse(); // 1.8's Login Start is the name alone

        // 1.8's Login Success names the UUID as a string; the game gets 26.3's layout.
        ByteBuf success = Unpooled.buffer();
        writeVarInt(success, 0x02);
        writeString(success, uuid.toString());
        writeString(success, "Steve");
        channel.writeInbound(success);

        assertThat(game).hasSize(1);
        ByteBuf translated = game.get(0);
        assertThat(readVarInt(translated)).isEqualTo(0x02);
        assertThat(new UUID(translated.readLong(), translated.readLong())).isEqualTo(uuid);
        assertThat(readString(translated)).isEqualTo("Steve");
        assertThat(readVarInt(translated)).isZero(); // properties
        assertThat(translated.readableBytes()).isEqualTo(16); // 26.2's session id
    }

    @Test
    void dropsWhatA18ServerHasNoUseFor() {
        IncariVia.load(47);
        List<ByteBuf> game = new ArrayList<>();
        EmbeddedChannel channel = channel(game);
        ViaPipeline.install(channel, 47, "splitter", "prepender");
        UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:Alex".getBytes(StandardCharsets.UTF_8));
        ByteBuf handshake = Unpooled.buffer();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, GAME);
        writeString(handshake, "localhost");
        handshake.writeShort(25565);
        writeVarInt(handshake, 2);
        ByteBuf hello = Unpooled.buffer();
        writeVarInt(hello, 0x00);
        writeString(hello, "Alex");
        hello.writeLong(uuid.getMostSignificantBits());
        hello.writeLong(uuid.getLeastSignificantBits());
        channel.writeOutbound(handshake, hello);
        ByteBuf sentHandshake = channel.readOutbound();
        ByteBuf sentHello = channel.readOutbound();
        sentHandshake.release();
        sentHello.release();
        ByteBuf success = Unpooled.buffer();
        writeVarInt(success, 0x02);
        writeString(success, uuid.toString());
        writeString(success, "Alex");
        channel.writeInbound(success);
        game.forEach(ByteBuf::release);

        // The game acknowledges the login, which 1.8 has no packet for: Via drops it, the write
        // succeeds, and nothing reaches the server.
        ByteBuf acknowledged = Unpooled.buffer();
        writeVarInt(acknowledged, 0x03);
        ChannelFuture write = channel.writeAndFlush(acknowledged);

        assertThat(write.isSuccess()).isTrue();
        Object sent = channel.readOutbound();
        assertThat(sent).isNull();
        // Every buffer the pipeline was handed is released, as Netty's codecs release them.
        assertThat(handshake.refCnt()).isZero();
        assertThat(hello.refCnt()).isZero();
        assertThat(success.refCnt()).isZero();
        assertThat(acknowledged.refCnt()).isZero();
    }

    /** "splitter" and "prepender" stand in for the game's framing; the tail is the game. */
    private static EmbeddedChannel channel(List<ByteBuf> game) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelDuplexHandler());
        channel.pipeline().addLast("game", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                game.add((ByteBuf) msg);
            }
        });
        return channel;
    }

    private static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = in.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    private static void writeString(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static String readString(ByteBuf in) {
        int length = readVarInt(in);
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }
}
