package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The client side of the EaglercraftX V5 handshake, the first stage of the game's pipeline (ADR
 * 0016). Eagler servers start their own login on a WebSocket's first binary message, log the player
 * in to their backend themselves, then carry vanilla packets, one per message. So this stage:
 *
 * <ol>
 *   <li>runs Eagler's exchange when the channel opens: client version, login request, profile data
 *       (the skin and cape), finish;
 *   <li>swallows what the game sends meanwhile (its handshake and Login Start), since the proxy
 *       already sent its own to the backend;
 *   <li>answers the game's login in the server's place, with a Login Success for the name and UUID
 *       the server granted (or a Login Disconnect carrying the server's refusal);
 *   <li>then passes vanilla packets both ways, keeping Eagler's own messages ({@code 0xEE}) for
 *       the {@link EaglerSession}.
 * </ol>
 *
 * <p>Packets are the Minecraft protocol of {@link EaglerLogin#minecraftProtocol()}: the game's own,
 * or the server's when Via translates between them above this stage.
 */
// Handshake packets go out with the void promise: a failed write fails the channel, which the
// game reports as a lost connection.
@SuppressWarnings("FutureReturnValueIgnored")
public final class EaglerHandshake extends ChannelDuplexHandler {

    /** The name this stage has in the pipeline. */
    public static final String NAME = "eagler";

    /** The handshake protocol this client speaks. */
    static final int EAGLER_PROTOCOL = 5;

    // Handshake packet ids (Eagler's, not Minecraft's).
    static final int CLIENT_VERSION = 0x01;
    static final int SERVER_VERSION = 0x02;
    static final int VERSION_MISMATCH = 0x03;
    static final int CLIENT_REQUEST_LOGIN = 0x04;
    static final int SERVER_ALLOW_LOGIN = 0x05;
    static final int SERVER_DENY_LOGIN = 0x06;
    static final int CLIENT_PROFILE_DATA = 0x07;
    static final int CLIENT_FINISH_LOGIN = 0x08;
    static final int SERVER_FINISH_LOGIN = 0x09;
    static final int SERVER_REDIRECT_TO = 0x0A;
    static final int SERVER_ERROR = 0xFF;

    /** The error code of a server that wants a password the client didn't offer. */
    static final int ERROR_AUTHENTICATION_REQUIRED = 0x09;
    /** How that error's message starts: "Authentication Required: [method] message". */
    private static final String AUTHENTICATION_REQUIRED = "Authentication Required:";

    private enum State {
        /** Not yet active. */
        IDLE,
        /** Sent our versions; waiting for the server's choice. */
        SENT_VERSION,
        /** Asked to log in; waiting for the server to allow or deny it. */
        SENT_LOGIN,
        /** Sent profile data and finished; waiting for the server's finish. */
        SENT_FINISH,
        /** The server finished; waiting for the game's Login Start before answering it. */
        LOGGED_IN,
        /** Answered the game's login; waiting for its Login Acknowledged (1.20.2 and later). */
        AWAITING_ACKNOWLEDGEMENT,
        /** Vanilla packets pass both ways. */
        PLAY,
        /** Refused or broken; everything is dropped until the channel closes. */
        FAILED
    }

    private final EaglerLogin login;
    private final EaglerSession session;
    /** Vanilla packets from the server held until the game is ready for them. Event loop only. */
    private final Queue<ByteBuf> held = new ArrayDeque<>();

    private State state = State.IDLE;
    /** Serverbound packets the game sent before play: its handshake, then Login Start. */
    private int swallowed;

    private boolean loginStartSeen;
    private @Nullable String grantedName;
    private @Nullable UUID grantedUuid;
    /** A refusal to show once the game can take a Login Disconnect. */
    private @Nullable String pendingDisconnect;
    /** The password method the server named, once it has; {@link EaglerPassword#NONE} before. */
    private int authMethod = EaglerPassword.NONE;
    /** Whether the login request carried a password, so a refusal may mean a wrong one. */
    private boolean sentPassword;
    /** Set when the server closed the connection mid-login without saying why. */
    private volatile boolean droppedDuringLogin;

    public EaglerHandshake(EaglerLogin login, EaglerSession session) {
        this.login = login;
        this.session = session;
    }

    /** Adds the stage at the head of {@code pipeline}, below framing, and returns it. */
    public static EaglerHandshake install(ChannelPipeline pipeline, EaglerLogin login, EaglerSession session) {
        EaglerHandshake stage = new EaglerHandshake(login, session);
        pipeline.addFirst(NAME, stage);
        return stage;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        sendClientVersion(ctx);
        state = State.SENT_VERSION;
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (state == State.SENT_VERSION || state == State.SENT_LOGIN || state == State.SENT_FINISH) {
            droppedDuringLogin = true;
        }
        releaseHeld();
        session.closed();
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf buffer)) {
            ctx.fireChannelRead(message);
            return;
        }
        switch (state) {
            case PLAY, LOGGED_IN, AWAITING_ACKNOWLEDGEMENT -> readPlay(ctx, buffer);
            case FAILED, IDLE -> buffer.release();
            default -> {
                try {
                    readHandshake(ctx, buffer);
                } catch (IndexOutOfBoundsException | DecoderException e) {
                    fail(ctx, "The server sent a malformed Eagler handshake packet");
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (state == State.PLAY || !(message instanceof ByteBuf buffer)) {
            ctx.write(message, promise);
            return;
        }
        int id = Wire.peekVarInt(buffer);
        boolean acknowledgement = state == State.AWAITING_ACKNOWLEDGEMENT
                && id == VanillaLogin.LOGIN_ACKNOWLEDGED_ID
                && buffer.readableBytes() == 1;
        // Nothing the game sends before play reaches the server: the proxy did the vanilla login.
        buffer.release();
        promise.trySuccess();
        swallowed++;
        if (acknowledgement) {
            enterPlay(ctx);
        } else if (!loginStartSeen && swallowed >= 2 && id == VanillaLogin.LOGIN_START_ID) {
            loginStartSeen = true;
            answerLogin(ctx);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseHeld();
    }

    /**
     * Whether the server silently closed the connection during Eagler's login. EaglerXServer does
     * that when its proxy can't speak the client's protocol (a BungeeCord older than the client),
     * so "Auto" takes it as a hint to try another version (ADR 0018).
     */
    public boolean droppedDuringLogin() {
        return droppedDuringLogin;
    }

    // --- The Eagler exchange ---------------------------------------------------------------

    private void sendClientVersion(ChannelHandlerContext ctx) {
        ByteBuf out = ctx.alloc().buffer();
        out.writeByte(CLIENT_VERSION);
        out.writeByte(0x02); // the V2+ handshake family
        out.writeShort(1);
        out.writeShort(EAGLER_PROTOCOL);
        out.writeShort(1);
        out.writeShort(login.minecraftProtocol());
        Wire.writeByteAscii(out, login.brand());
        Wire.writeByteAscii(out, login.version());
        // Offering a password makes an auth plugin send its method and salt instead of refusing.
        out.writeBoolean(login.password() != null);
        Wire.writeByteAscii(out, login.username());
        ctx.writeAndFlush(out, ctx.voidPromise());
    }

    private void readHandshake(ChannelHandlerContext ctx, ByteBuf in) {
        int id = in.readUnsignedByte();
        switch (id) {
            case SERVER_VERSION -> {
                if (state != State.SENT_VERSION) {
                    fail(ctx, "The server sent its version twice");
                    return;
                }
                readServerVersion(ctx, in);
            }
            case SERVER_ALLOW_LOGIN -> {
                if (state != State.SENT_LOGIN) {
                    fail(ctx, "The server allowed a login it wasn't asked for");
                    return;
                }
                readAllowLogin(ctx, in);
            }
            case SERVER_FINISH_LOGIN -> {
                if (state != State.SENT_FINISH) {
                    fail(ctx, "The server finished a login too early");
                    return;
                }
                state = State.LOGGED_IN;
                if (loginStartSeen) {
                    answerLogin(ctx);
                }
            }
            case VERSION_MISMATCH -> fail(ctx, versionMismatch(in));
            case SERVER_DENY_LOGIN -> {
                String reason = Wire.readShortUtf8(in);
                if (sentPassword) {
                    // An auth plugin's refusal of the password sent: ask for it again.
                    session.authenticationRequired(authMethod, reason);
                }
                fail(ctx, reason);
            }
            case SERVER_REDIRECT_TO -> {
                String address = Wire.readShortUtf8(in);
                session.redirected(address);
                fail(ctx, "The server redirected this client to " + address);
            }
            case SERVER_ERROR -> readServerError(ctx, in);
            default -> fail(ctx, "The server sent an unknown Eagler handshake packet (" + id + ")");
        }
    }

    private void readServerVersion(ChannelHandlerContext ctx, ByteBuf in) {
        int eaglerProtocol = in.readUnsignedShort();
        int minecraftProtocol = in.readUnsignedShort();
        Wire.readByteAscii(in); // server brand
        Wire.readByteAscii(in); // server version
        int method = in.readUnsignedByte();
        byte[] salt = new byte[in.readUnsignedShort()];
        in.readBytes(salt);
        // V5 then says whether the player may pick a name other than the one offered; the name
        // offered is the one wanted, so there's nothing to do with it.
        if (eaglerProtocol != EAGLER_PROTOCOL) {
            fail(ctx, "The server chose Eagler protocol " + eaglerProtocol + "; this client speaks " + EAGLER_PROTOCOL);
            return;
        }
        if (minecraftProtocol != login.minecraftProtocol()) {
            fail(
                    ctx,
                    "The server chose Minecraft protocol " + minecraftProtocol + ", not " + login.minecraftProtocol());
            return;
        }
        authMethod = method;
        byte[] answer = new byte[0];
        EaglerPassword password = login.password();
        if (method != EaglerPassword.NONE && password != null) {
            byte[] hashed = password.answer(method, salt);
            if (hashed == null) {
                if (method == EaglerPassword.PLAINTEXT) {
                    session.authenticationRequired(method, "");
                    fail(ctx, "This server wants your password as plain text, and you haven't allowed that");
                } else {
                    fail(ctx, "This server uses a login method Incari doesn't support (method " + method + ")");
                }
                return;
            }
            answer = hashed;
        }
        // With a method but no password, the server takes a cookie instead or says it wants one.
        sentPassword = answer.length > 0;
        EaglerLogin.Cookie cookie = login.cookie();
        byte[] cookieData = cookie != null ? cookie.data() : new byte[0];
        ByteBuf out = ctx.alloc().buffer();
        out.writeByte(CLIENT_REQUEST_LOGIN);
        Wire.writeByteAscii(out, login.username());
        Wire.writeByteAscii(out, login.requestedServer());
        out.writeByte(answer.length);
        out.writeBytes(answer);
        boolean cookies = (login.capabilities() & EaglerLogin.COOKIE) != 0;
        out.writeBoolean(cookies);
        out.writeByte(cookies ? cookieData.length : 0);
        if (cookies) {
            out.writeBytes(cookieData);
        }
        int capabilities = login.capabilities();
        Wire.writeVarInt(out, capabilities);
        for (int i = 0; i < Integer.bitCount(capabilities); i++) {
            Wire.writeVarInt(out, 1); // each at version 0 (bit 0 of its versions)
        }
        out.writeByte(0); // no extended capabilities
        ctx.writeAndFlush(out, ctx.voidPromise());
        state = State.SENT_LOGIN;
    }

    private void readAllowLogin(ChannelHandlerContext ctx, ByteBuf in) {
        String name = Wire.readByteAscii(in);
        UUID uuid = new UUID(in.readLong(), in.readLong());
        int accepted = 0;
        if (in.isReadable()) {
            accepted = Wire.readVarInt(in);
            in.skipBytes(Integer.bitCount(accepted)); // the version of each, only ever 0 here
            // Extended capabilities follow; none were asked for.
        }
        session.capabilities(accepted & login.capabilities());
        grantedName = name;
        grantedUuid = uuid;

        if (!login.profileData().isEmpty()) {
            ByteBuf out = ctx.alloc().buffer();
            out.writeByte(CLIENT_PROFILE_DATA);
            out.writeByte(login.profileData().size());
            for (EaglerLogin.ProfileData data : login.profileData()) {
                Wire.writeByteAscii(out, data.type());
                out.writeShort(data.data().length);
                out.writeBytes(data.data());
            }
            ctx.write(out, ctx.voidPromise());
        }
        ByteBuf finish = ctx.alloc().buffer(1);
        finish.writeByte(CLIENT_FINISH_LOGIN);
        ctx.writeAndFlush(finish, ctx.voidPromise());
        state = State.SENT_FINISH;
    }

    private static String versionMismatch(ByteBuf in) {
        in.skipBytes(in.readUnsignedShort() * 2); // Eagler protocols the server speaks
        int ranges = in.readUnsignedShort();
        int min = ranges >= 2 ? in.readUnsignedShort() : -1;
        int max = ranges >= 2 ? in.readUnsignedShort() : -1;
        in.skipBytes(Math.max(0, ranges - 2) * 2);
        String reason = in.isReadable() ? Wire.readByteAscii(in) : "Unsupported client version";
        return reason + (min >= 0 ? " (the server takes Minecraft protocols " + min + " to " + max + ")" : "");
    }

    private void readServerError(ChannelHandlerContext ctx, ByteBuf in) {
        int code = in.readUnsignedByte();
        // A short-prefixed message, or a byte-prefixed one when the server answers before it has
        // chosen the protocol (an auth plugin's first refusal comes that early).
        String message = in.readableBytes() >= 2 && in.getUnsignedShort(in.readerIndex()) == in.readableBytes() - 2
                ? Wire.readShortUtf8(in)
                : Wire.readByteAscii(in);
        if (code == ERROR_AUTHENTICATION_REQUIRED && message.startsWith(AUTHENTICATION_REQUIRED)) {
            // "Authentication Required: [1] Log in with your password"
            String rest = message.substring(AUTHENTICATION_REQUIRED.length()).trim();
            int method = EaglerPassword.NONE;
            int close = rest.indexOf(']');
            if (rest.startsWith("[") && close > 0) {
                try {
                    method = Integer.parseInt(rest.substring(1, close));
                } catch (NumberFormatException e) {
                    // Left at NONE: the screen asks for a password all the same.
                }
                rest = rest.substring(close + 1).trim();
            }
            session.authenticationRequired(method, rest);
            fail(ctx, rest.isEmpty() ? "This server needs a password" : rest);
            return;
        }
        fail(ctx, message);
    }

    // --- Standing in for the vanilla login ------------------------------------------------

    /** Once the server finished and the game is waiting for its login: Login Success. */
    private void answerLogin(ChannelHandlerContext ctx) {
        if (pendingDisconnect != null) {
            String reason = pendingDisconnect;
            pendingDisconnect = null;
            ctx.fireChannelRead(VanillaLogin.loginDisconnect(ctx.alloc(), reason));
            return;
        }
        String name = grantedName;
        UUID uuid = grantedUuid;
        if (state != State.LOGGED_IN || name == null || uuid == null) {
            return;
        }
        session.loggedIn(name, uuid, EaglerMessages.on(ctx));
        boolean acknowledges = VanillaLogin.acknowledgesLogin(login.minecraftProtocol());
        state = acknowledges ? State.AWAITING_ACKNOWLEDGEMENT : State.PLAY;
        ctx.fireChannelRead(VanillaLogin.loginSuccess(ctx.alloc(), login.minecraftProtocol(), name, uuid));
        if (!acknowledges) {
            flushHeld(ctx);
        }
    }

    private void enterPlay(ChannelHandlerContext ctx) {
        state = State.PLAY;
        flushHeld(ctx);
    }

    private void readPlay(ChannelHandlerContext ctx, ByteBuf buffer) {
        if (buffer.isReadable() && buffer.getUnsignedByte(buffer.readerIndex()) == EaglerMessages.PREFIX) {
            try {
                buffer.skipBytes(1);
                EaglerMessages.dispatch(buffer, session);
            } catch (IndexOutOfBoundsException | DecoderException e) {
                // A malformed extra (a skin) mustn't end the game's connection.
                ctx.fireExceptionCaught(new DecoderException("Malformed Eagler message", e));
            } finally {
                buffer.release();
            }
            return;
        }
        if (state == State.PLAY) {
            ctx.fireChannelRead(buffer);
        } else {
            // The server sends the backend's packets as soon as it finishes; the game isn't ready
            // for them until it has taken the login (and, from 1.20.2, acknowledged it).
            held.add(buffer);
        }
    }

    private void flushHeld(ChannelHandlerContext ctx) {
        while (!held.isEmpty() && state == State.PLAY) {
            ctx.fireChannelRead(held.remove());
        }
    }

    private void releaseHeld() {
        while (!held.isEmpty()) {
            held.remove().release();
        }
    }

    /**
     * Ends the login with {@code reason} shown to the player: as a Login Disconnect once the game is
     * waiting for its login (it shows the reason as a kick), then the connection closes.
     */
    private void fail(ChannelHandlerContext ctx, String reason) {
        state = State.FAILED;
        releaseHeld();
        if (loginStartSeen) {
            ctx.fireChannelRead(VanillaLogin.loginDisconnect(ctx.alloc(), reason));
        } else {
            // Delivered when the game's Login Start comes through write(). The game sends it the
            // moment the channel opens, long before any answer can arrive, so this is rare.
            pendingDisconnect = reason;
        }
    }
}
