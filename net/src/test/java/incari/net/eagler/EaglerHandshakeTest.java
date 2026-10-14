package incari.net.eagler;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The Eagler stage against a scripted server, with the channel's inbound end standing in for the game. */
class EaglerHandshakeTest {

    private static final UUID GRANTED = UUID.fromString("0b6b1b5a-6c86-3b8c-9a63-2d5d2e3b0f11");

    private final Session session = new Session();
    private final List<ByteBuf> game = new ArrayList<>();

    private EmbeddedChannel channel(int protocol, List<EaglerLogin.ProfileData> profile) {
        return channel(new EaglerLogin("Steve", protocol, "Incari", "test", "", profile));
    }

    private static EaglerLogin login(
            int protocol, @Nullable EaglerPassword password, byte @Nullable [] cookie, int capabilities) {
        return new EaglerLogin(
                "Steve",
                protocol,
                "Incari",
                "test",
                "",
                List.of(),
                password,
                cookie == null ? null : new EaglerLogin.Cookie(cookie),
                capabilities);
    }

    private EmbeddedChannel channel(EaglerLogin login) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                game.add((ByteBuf) msg);
            }
        });
        EaglerHandshake.install(channel.pipeline(), login, session);
        channel.pipeline().fireChannelActive();
        return channel;
    }

    @Test
    void logsInAndStandsInForTheVanillaLogin() {
        EaglerLogin.ProfileData skin = EaglerTextures.presetSkinProfileData(3);
        EmbeddedChannel channel = channel(777, List.of(skin));

        ByteBuf version = channel.readOutbound();
        assertThat((int) version.readUnsignedByte()).isEqualTo(0x01);
        assertThat((int) version.readUnsignedByte()).isEqualTo(0x02);
        assertThat(version.readUnsignedShort()).isEqualTo(1);
        assertThat(version.readUnsignedShort()).isEqualTo(5);
        assertThat(version.readUnsignedShort()).isEqualTo(1);
        assertThat(version.readUnsignedShort()).isEqualTo(777);
        assertThat(Wire.readByteAscii(version)).isEqualTo("Incari");
        assertThat(Wire.readByteAscii(version)).isEqualTo("test");
        assertThat(version.readBoolean()).isFalse();
        assertThat(Wire.readByteAscii(version)).isEqualTo("Steve");
        assertThat(version.isReadable()).isFalse();

        // The game's handshake and Login Start never reach the server.
        channel.writeOutbound(packet(0x00, 777), packet(0x00, "Steve"));
        assertThat(channel.outboundMessages()).isEmpty();

        channel.writeInbound(serverVersion(5, 777, 0));
        ByteBuf request = channel.readOutbound();
        assertThat((int) request.readUnsignedByte()).isEqualTo(0x04);
        assertThat(Wire.readByteAscii(request)).isEqualTo("Steve");
        assertThat(Wire.readByteAscii(request)).isEmpty();
        assertThat((int) request.readUnsignedByte()).isZero(); // password
        assertThat(request.readBoolean()).isFalse(); // cookie
        assertThat((int) request.readUnsignedByte()).isZero();
        assertThat(Wire.readVarInt(request)).isZero(); // capabilities
        assertThat((int) request.readUnsignedByte()).isZero();
        assertThat(request.isReadable()).isFalse();

        channel.writeInbound(allowLogin("Steve2", GRANTED));
        ByteBuf profile = channel.readOutbound();
        assertThat((int) profile.readUnsignedByte()).isEqualTo(0x07);
        assertThat((int) profile.readUnsignedByte()).isEqualTo(1);
        assertThat(Wire.readByteAscii(profile)).isEqualTo("skin_v2");
        assertThat(profile.readUnsignedShort()).isEqualTo(5);
        assertThat((int) profile.readUnsignedByte()).isEqualTo(1);
        assertThat(profile.readInt()).isEqualTo(3);
        ByteBuf finish = channel.readOutbound();
        assertThat((int) finish.readUnsignedByte()).isEqualTo(0x08);
        assertThat(game).isEmpty();

        // The server finishes and sends the backend's first packets at once.
        channel.writeInbound(bytes(0x09), packet(0x0E, "config"));
        assertThat(session.name).isEqualTo("Steve2");
        assertThat(game).hasSize(1);
        ByteBuf success = game.get(0);
        assertThat(Wire.readVarInt(success)).isEqualTo(0x02);
        assertThat(new UUID(success.readLong(), success.readLong())).isEqualTo(GRANTED);
        assertThat(Wire.readString(success)).isEqualTo("Steve2");
        assertThat(Wire.readVarInt(success)).isZero(); // properties
        success.skipBytes(16); // 26.2 and later: the session id
        assertThat(success.isReadable()).isFalse();

        // Held until the game acknowledges the login, which isn't passed on either.
        channel.writeOutbound(packet(0x03));
        assertThat(channel.outboundMessages()).isEmpty();
        assertThat(game).hasSize(2);
        assertThat(Wire.readVarInt(game.get(1))).isEqualTo(0x0E);

        // Then packets pass both ways, and Eagler's own messages go to the session.
        channel.writeOutbound(packet(0x02, "chat"));
        ByteBuf out = channel.readOutbound();
        assertThat(Wire.readVarInt(out)).isEqualTo(0x02);
        channel.writeInbound(bytes(0xEE, 0x05, 0x01, 0x02));
        assertThat(session.messages).containsExactly("5:0102");
        assertThat(game).hasSize(2);
    }

    @Test
    void anOlderServerNeedsNoAcknowledgement() {
        EmbeddedChannel channel = channel(47, List.of());
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 47), packet(0x00, "Steve"));
        channel.writeInbound(serverVersion(5, 47, 0));
        channel.readOutbound();
        channel.writeInbound(allowLogin("Steve", GRANTED));
        ByteBuf finish = channel.readOutbound();
        assertThat((int) finish.readUnsignedByte()).isEqualTo(0x08);

        channel.writeInbound(bytes(0x09), packet(0x01, "join game"));

        assertThat(game).hasSize(2);
        ByteBuf success = game.get(0);
        assertThat(Wire.readVarInt(success)).isEqualTo(0x02);
        assertThat(Wire.readString(success)).isEqualTo(GRANTED.toString());
        assertThat(Wire.readString(success)).isEqualTo("Steve");
        assertThat(success.isReadable()).isFalse();
        assertThat(Wire.readVarInt(game.get(1))).isEqualTo(0x01);
    }

    @Test
    void waitsForTheGamesLoginStartBeforeAnswering() {
        EmbeddedChannel channel = channel(47, List.of());
        channel.readOutbound();
        channel.writeInbound(serverVersion(5, 47, 0));
        channel.readOutbound();
        channel.writeInbound(allowLogin("Steve", GRANTED));
        channel.readOutbound();
        channel.readOutbound();
        channel.writeInbound(bytes(0x09), packet(0x01, "join game"));
        assertThat(game).isEmpty();

        channel.writeOutbound(packet(0x00, 47), packet(0x00, "Steve"));

        assertThat(game).hasSize(2);
    }

    @Test
    void aRefusalBecomesALoginDisconnect() {
        EmbeddedChannel channel = channel(777, List.of());
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 777), packet(0x00, "Steve"));

        ByteBuf error = Unpooled.buffer();
        error.writeByte(0xFF);
        error.writeByte(0x08);
        byte[] message = "You are \"banned\"".getBytes(StandardCharsets.UTF_8);
        error.writeShort(message.length);
        error.writeBytes(message);
        channel.writeInbound(error);

        assertThat(game).hasSize(1);
        ByteBuf disconnect = game.get(0);
        assertThat(Wire.readVarInt(disconnect)).isEqualTo(0x00);
        assertThat(Wire.readString(disconnect)).isEqualTo("{\"text\":\"You are \\\"banned\\\"\"}");
    }

    @Test
    void aPasswordServerWithoutAPasswordGetsAnEmptyOneAndTheCookie() {
        // A server that names a method although none was offered takes a cookie instead.
        EmbeddedChannel channel = channel(login(777, null, new byte[] {9, 8, 7}, EaglerLogin.COOKIE));
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 777), packet(0x00, "Steve"));

        channel.writeInbound(serverVersion(5, 777, EaglerPassword.EAGLER_SHA256, new byte[64]));

        ByteBuf request = channel.readOutbound();
        request.skipBytes(1);
        Wire.readByteAscii(request);
        Wire.readByteAscii(request);
        assertThat((int) request.readUnsignedByte()).isZero();
        assertThat(request.readBoolean()).isTrue();
        assertThat((int) request.readUnsignedByte()).isEqualTo(3);
        assertThat(request.readByte()).isEqualTo((byte) 9);
        request.skipBytes(2);
        assertThat(Wire.readVarInt(request)).isEqualTo(EaglerLogin.COOKIE);
        assertThat(Wire.readVarInt(request)).isEqualTo(1);
        assertThat((int) request.readUnsignedByte()).isZero();
        assertThat(request.isReadable()).isFalse();
    }

    @Test
    void sendsThePasswordInTheServersForm() {
        EaglerPassword password = new EaglerPassword("hunter2", false);
        EmbeddedChannel channel = channel(login(47, password, null, 0));
        ByteBuf version = channel.readOutbound();
        version.skipBytes(1 + 1 + 2 + 2 + 2 + 2);
        Wire.readByteAscii(version);
        Wire.readByteAscii(version);
        assertThat(version.readBoolean()).isTrue(); // a password is offered

        byte[] salt = new byte[64];
        for (int i = 0; i < 64; i++) {
            salt[i] = (byte) i;
        }
        channel.writeInbound(serverVersion(5, 47, EaglerPassword.EAGLER_SHA256, salt));
        ByteBuf request = channel.readOutbound();
        request.skipBytes(1);
        Wire.readByteAscii(request);
        Wire.readByteAscii(request);
        byte[] sent = new byte[request.readUnsignedByte()];
        request.readBytes(sent);
        // Computed independently (Python's hashlib) from the protocol's description.
        assertThat(hex(sent)).isEqualTo("74333fa9174c0f16c2cdf3ac1e6b3376b71f1b93f32a7e4303b16867a4fab0cf");
    }

    @Test
    void answersAuthMeWithItsOwnHash() {
        byte[] answer = new EaglerPassword("hunter2", false)
                .answer(EaglerPassword.AUTHME_SHA256, "0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        // AuthMe stores "$SHA$0123456789abcdef$" and this hash for "hunter2".
        assertThat(new String(answer, StandardCharsets.US_ASCII))
                .isEqualTo("a8e5b4e6828c1d5c2dd411422a06f740b88a6267b74dd4cea0424c6e4c01c095");
    }

    @Test
    void sendsPlainTextOnlyWithConsent() {
        byte[] salt = new byte[0];
        assertThat(new EaglerPassword("pw", false).answer(EaglerPassword.PLAINTEXT, salt))
                .isNull();
        assertThat(new EaglerPassword("pw", true).answer(EaglerPassword.PLAINTEXT, salt))
                .containsExactly(0, 'p', 0, 'w');

        EmbeddedChannel channel = channel(login(47, new EaglerPassword("pw", false), null, 0));
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 47), packet(0x00, "Steve"));
        channel.writeInbound(serverVersion(5, 47, EaglerPassword.PLAINTEXT, salt));

        assertThat(session.authRequired).isEqualTo(EaglerPassword.PLAINTEXT + ":");
        assertThat(channel.outboundMessages()).isEmpty();
        assertThat(game).hasSize(1); // the Login Disconnect
    }

    @Test
    void aServerThatWantsAPasswordSaysSo() {
        EmbeddedChannel channel = channel(777, List.of());
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 777), packet(0x00, "Steve"));

        // Sent before the server has chosen a protocol: a byte-length message.
        ByteBuf error = Unpooled.buffer();
        error.writeByte(0xFF);
        error.writeByte(0x09);
        Wire.writeByteAscii(error, "Authentication Required: [2] Log in with /login");
        channel.writeInbound(error);

        assertThat(session.authRequired).isEqualTo("2:Log in with /login");
        ByteBuf disconnect = game.get(0);
        Wire.readVarInt(disconnect);
        assertThat(Wire.readString(disconnect)).contains("Log in with /login");
    }

    @Test
    void aRefusedPasswordAsksAgain() {
        EmbeddedChannel channel = channel(login(47, new EaglerPassword("wrong", false), null, 0));
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 47), packet(0x00, "Steve"));
        channel.writeInbound(serverVersion(5, 47, EaglerPassword.AUTHME_SHA256, new byte[16]));
        channel.readOutbound();

        ByteBuf deny = Unpooled.buffer();
        deny.writeByte(0x06);
        byte[] reason = "{\"text\":\"Wrong password\"}".getBytes(StandardCharsets.UTF_8);
        deny.writeShort(reason.length);
        deny.writeBytes(reason);
        channel.writeInbound(deny);

        assertThat(session.authRequired).isEqualTo("2:{\"text\":\"Wrong password\"}");
    }

    @Test
    void asksForCapabilitiesAndHearsWhichWereAccepted() {
        EmbeddedChannel channel = channel(login(47, null, null, EaglerLogin.VOICE | EaglerLogin.COOKIE));
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 47), packet(0x00, "Steve"));
        channel.writeInbound(serverVersion(5, 47, 0));
        ByteBuf request = channel.readOutbound();
        request.skipBytes(1);
        Wire.readByteAscii(request);
        Wire.readByteAscii(request);
        request.skipBytes(1);
        assertThat(request.readBoolean()).isTrue(); // cookies allowed
        assertThat((int) request.readUnsignedByte()).isZero(); // none stored yet
        assertThat(Wire.readVarInt(request)).isEqualTo(EaglerLogin.VOICE | EaglerLogin.COOKIE);
        assertThat(Wire.readVarInt(request)).isEqualTo(1);
        assertThat(Wire.readVarInt(request)).isEqualTo(1);

        ByteBuf allow = Unpooled.buffer();
        allow.writeByte(0x05);
        Wire.writeByteAscii(allow, "Steve");
        allow.writeLong(GRANTED.getMostSignificantBits());
        allow.writeLong(GRANTED.getLeastSignificantBits());
        Wire.writeVarInt(allow, EaglerLogin.VOICE);
        allow.writeByte(0);
        allow.writeByte(0);
        channel.writeInbound(allow);

        assertThat(session.capabilities).isEqualTo(EaglerLogin.VOICE);
    }

    @Test
    void bundledMessagesAreSplit() {
        EmbeddedChannel channel = channel(47, List.of());
        channel.readOutbound();
        channel.writeOutbound(packet(0x00, 47), packet(0x00, "Steve"));
        channel.writeInbound(serverVersion(5, 47, 0));
        channel.readOutbound();
        channel.writeInbound(allowLogin("Steve", GRANTED));
        channel.readOutbound();
        channel.readOutbound();
        channel.writeInbound(bytes(0x09));

        channel.writeInbound(bytes(0xEE, 0xFF, 0x02, 0x02, 0x16, 0x00, 0x03, 0x01, 0x07, 0x08));

        assertThat(session.messages).containsExactly("22:00", "1:0708");
    }

    // --- The scripted server's packets ---------------------------------------------------

    private static ByteBuf serverVersion(int eagler, int minecraft, int authMethod) {
        return serverVersion(eagler, minecraft, authMethod, new byte[0]);
    }

    private static ByteBuf serverVersion(int eagler, int minecraft, int authMethod, byte[] salt) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(0x02);
        out.writeShort(eagler);
        out.writeShort(minecraft);
        Wire.writeByteAscii(out, "EaglerXServer");
        Wire.writeByteAscii(out, "1.1.1");
        out.writeByte(authMethod);
        out.writeShort(salt.length);
        out.writeBytes(salt);
        out.writeBoolean(false);
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    private static ByteBuf allowLogin(String name, UUID uuid) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(0x05);
        Wire.writeByteAscii(out, name);
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
        Wire.writeVarInt(out, 0);
        out.writeByte(0);
        return out;
    }

    /** A vanilla packet: its id, then an int or a string. */
    private static ByteBuf packet(int id, Object... fields) {
        ByteBuf out = Unpooled.buffer();
        Wire.writeVarInt(out, id);
        for (Object field : fields) {
            if (field instanceof Integer number) {
                Wire.writeVarInt(out, number);
            } else {
                Wire.writeString(out, (String) field);
            }
        }
        return out;
    }

    private static ByteBuf bytes(int... values) {
        ByteBuf out = Unpooled.buffer();
        for (int value : values) {
            out.writeByte(value);
        }
        return out;
    }

    private static final class Session implements EaglerSession {
        @Nullable
        String name;

        final List<String> messages = new ArrayList<>();

        @Nullable
        String authRequired;

        int capabilities = -1;

        @Override
        public void loggedIn(String name, UUID uuid, EaglerMessages messages) {
            this.name = name;
        }

        @Override
        public void capabilities(int accepted) {
            capabilities = accepted;
        }

        @Override
        public void authenticationRequired(int method, String message) {
            authRequired = method + ":" + message;
        }

        @Override
        public void received(int id, ByteBuf body) {
            StringBuilder hex = new StringBuilder();
            while (body.isReadable()) {
                int b = body.readUnsignedByte();
                hex.append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            messages.add(id + ":" + hex);
        }
    }
}
