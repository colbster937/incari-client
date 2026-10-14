package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.UUID;

/**
 * The vanilla login packets the Eagler stage writes to the game in the server's place. An Eagler
 * proxy logs the player in to its backend itself; the client only sees Eagler's own exchange. So
 * the stage answers the game's login with the packets a server would have sent, in the protocol
 * version the proxy was told (the connection's Minecraft protocol, which is the server's version
 * when Via translates above this stage).
 */
final class VanillaLogin {

    /** 1.16: the player's UUID is written as two longs instead of a string. */
    static final int BINARY_UUID = 735;
    /** 1.19: Login Success carries the profile's properties. */
    static final int PROFILE_PROPERTIES = 759;
    /** 1.20.2: login ends with the client's Login Acknowledged and a configuration phase. */
    static final int LOGIN_ACKNOWLEDGED = 764;
    /** 1.20.5 to 1.21.1: Login Success ends with a "strict error handling" flag. */
    static final int STRICT_ERRORS_FROM = 766;

    static final int STRICT_ERRORS_UNTIL = 767;
    /** 26.2: Login Success ends with a session id. */
    static final int SESSION_ID = 776;

    /** Clientbound login packet ids, the same in every version. */
    static final int LOGIN_DISCONNECT_ID = 0x00;

    static final int LOGIN_SUCCESS_ID = 0x02;
    /** Serverbound: the handshake (in its own state) and Login Start both have id 0. */
    static final int HANDSHAKE_ID = 0x00;

    static final int LOGIN_START_ID = 0x00;
    static final int LOGIN_ACKNOWLEDGED_ID = 0x03;

    private VanillaLogin() {}

    /** Whether a server of this protocol expects Login Acknowledged before configuration. */
    static boolean acknowledgesLogin(int protocol) {
        return protocol >= LOGIN_ACKNOWLEDGED;
    }

    /** Login Success for {@code name} and {@code uuid}, in the layout {@code protocol} uses. */
    static ByteBuf loginSuccess(ByteBufAllocator alloc, int protocol, String name, UUID uuid) {
        ByteBuf out = alloc.buffer();
        Wire.writeVarInt(out, LOGIN_SUCCESS_ID);
        if (protocol >= BINARY_UUID) {
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        } else {
            Wire.writeString(out, uuid.toString());
        }
        Wire.writeString(out, name);
        if (protocol >= PROFILE_PROPERTIES) {
            Wire.writeVarInt(out, 0); // no properties: offline profile
        }
        if (protocol >= STRICT_ERRORS_FROM && protocol <= STRICT_ERRORS_UNTIL) {
            out.writeBoolean(false);
        }
        if (protocol >= SESSION_ID) {
            // Fresh per login: the proxy chose none, and the game only uses it to tell sessions apart.
            UUID session = UUID.randomUUID();
            out.writeLong(session.getMostSignificantBits());
            out.writeLong(session.getLeastSignificantBits());
        }
        return out;
    }

    /**
     * A login-state disconnect showing {@code message}: a JSON text component (every version's
     * login disconnect is JSON), or plain text, which is wrapped in one.
     */
    static ByteBuf loginDisconnect(ByteBufAllocator alloc, String message) {
        ByteBuf out = alloc.buffer();
        Wire.writeVarInt(out, LOGIN_DISCONNECT_ID);
        Wire.writeString(out, message.startsWith("{") ? message : jsonText(message));
        return out;
    }

    static String jsonText(String text) {
        StringBuilder json = new StringBuilder(text.length() + 12).append("{\"text\":\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                default -> {
                    if (c < 0x20) {
                        json.append(c < 0x10 ? "\\u000" : "\\u00").append(Integer.toHexString(c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append("\"}").toString();
    }
}
