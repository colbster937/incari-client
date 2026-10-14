package incari.tools.wsbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * The backend's vanilla status (the server list ping), turned into the MOTD answer an Eagler
 * server gives a query ({@code Accept: motd}): up to two MOTD lines in legacy formatting, player
 * counts and names, the icon as 64x64 RGBA pixels, and (an Incari extension, which Eagler servers
 * don't send) the backend's protocol version, so the client's automatic version choice finds it.
 */
// Writes and closes are fire-and-forget: a failed one closes its channel, which fails the future.
@SuppressWarnings("FutureReturnValueIgnored")
final class BackendStatus {

    private BackendStatus() {}

    /** The Eagler MOTD for the server at {@code target}; fails if it doesn't answer. */
    static CompletableFuture<Motd> query(EventLoop loop, InetSocketAddress target) {
        CompletableFuture<Motd> result = new CompletableFuture<>();
        new Bootstrap()
                .group(loop)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new VarIntFrames.Splitter(), new StatusReader(result));
                    }
                })
                .connect(target)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        result.completeExceptionally(future.cause());
                        return;
                    }
                    Channel channel = future.channel();
                    ByteBuf handshake = channel.alloc().buffer();
                    Packets.writeVarInt(handshake, 0x00);
                    Packets.writeVarInt(handshake, -1); // any version: the answer is the same
                    Packets.writeString(handshake, target.getHostString());
                    handshake.writeShort(target.getPort());
                    Packets.writeVarInt(handshake, 1); // status
                    channel.write(framed(channel, handshake));
                    ByteBuf request = channel.alloc().buffer(1);
                    Packets.writeVarInt(request, 0x00);
                    channel.writeAndFlush(framed(channel, request));
                });
        return result;
    }

    private static ByteBuf framed(Channel channel, ByteBuf packet) {
        ByteBuf out = channel.alloc().buffer(packet.readableBytes() + 3);
        VarIntFrames.writeLength(out, packet.readableBytes());
        out.writeBytes(packet);
        packet.release();
        return out;
    }

    /** The parts of the MOTD answer. The icon is only ever read, to send it. */
    @SuppressWarnings("ArrayRecordComponent")
    record Motd(List<String> lines, int online, int max, List<String> players, byte @Nullable [] icon, int protocol) {

        JsonObject toJson() {
            JsonObject data = new JsonObject();
            data.addProperty("cache", false);
            JsonArray motd = new JsonArray();
            lines.forEach(motd::add);
            data.add("motd", motd);
            data.addProperty("icon", icon != null);
            data.addProperty("online", online);
            data.addProperty("max", max);
            JsonArray names = new JsonArray();
            players.forEach(names::add);
            data.add("players", names);
            data.addProperty("protocol", protocol);
            return data;
        }
    }

    private static final class StatusReader extends ChannelInboundHandlerAdapter {

        private final CompletableFuture<Motd> result;

        StatusReader(CompletableFuture<Motd> result) {
            this.result = result;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            ByteBuf packet = (ByteBuf) message;
            try {
                if (Packets.readVarInt(packet) == 0x00) {
                    result.complete(parse(Packets.readString(packet)));
                }
            } catch (RuntimeException | IOException e) {
                result.completeExceptionally(e);
            } finally {
                packet.release();
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            result.completeExceptionally(new IOException("The server closed without a status"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            result.completeExceptionally(cause);
            ctx.close();
        }
    }

    static Motd parse(String json) throws IOException {
        JsonObject status = JsonParser.parseString(json).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        if (status.has("description")) {
            appendLegacy(text, status.get("description"));
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.toString().split("\n", -1)) {
            if (lines.size() < 2) {
                lines.add(line);
            }
        }
        int online = 0;
        int max = 0;
        List<String> names = new ArrayList<>();
        if (status.has("players")) {
            JsonObject players = status.getAsJsonObject("players");
            online = players.get("online").getAsInt();
            max = players.get("max").getAsInt();
            if (players.has("sample")) {
                for (JsonElement sample : players.getAsJsonArray("sample")) {
                    names.add(sample.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        int protocol = status.has("version")
                ? status.getAsJsonObject("version").get("protocol").getAsInt()
                : -1;
        byte[] icon = status.has("favicon") ? icon(status.get("favicon").getAsString()) : null;
        return new Motd(lines, online, max, names, icon, protocol);
    }

    /** Text components flattened to legacy formatting codes, as Eagler MOTDs are written. */
    private static void appendLegacy(StringBuilder out, JsonElement component) {
        if (component.isJsonPrimitive()) {
            out.append(component.getAsString());
            return;
        }
        if (component.isJsonArray()) {
            for (JsonElement part : component.getAsJsonArray()) {
                appendLegacy(out, part);
            }
            return;
        }
        JsonObject object = component.getAsJsonObject();
        if (object.has("color")) {
            String code = colorCode(object.get("color").getAsString());
            if (code != null) {
                out.append('§').append(code);
            }
        }
        if (object.has("text")) {
            out.append(object.get("text").getAsString());
        }
        if (object.has("extra")) {
            appendLegacy(out, object.get("extra"));
        }
    }

    private static @Nullable String colorCode(String name) {
        String[] names = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
        };
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return Integer.toHexString(i);
            }
        }
        return null;
    }

    /** A {@code data:image/png;base64,...} favicon as 64x64 RGBA bytes. */
    private static byte @Nullable [] icon(String favicon) throws IOException {
        int comma = favicon.indexOf(',');
        if (comma < 0) {
            return null;
        }
        BufferedImage image =
                ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(favicon.substring(comma + 1))));
        if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
            return null;
        }
        byte[] rgba = new byte[64 * 64 * 4];
        for (int i = 0; i < 64 * 64; i++) {
            int argb = image.getRGB(i % 64, i / 64);
            rgba[i * 4] = (byte) (argb >>> 16);
            rgba[i * 4 + 1] = (byte) (argb >>> 8);
            rgba[i * 4 + 2] = (byte) argb;
            rgba[i * 4 + 3] = (byte) (argb >>> 24);
        }
        return rgba;
    }
}
