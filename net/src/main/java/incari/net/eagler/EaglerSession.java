package incari.net.eagler;

import io.netty.buffer.ByteBuf;
import java.util.UUID;

/**
 * Hears one Eagler connection after its handshake: the login the server granted, and Eagler's own
 * messages (skins, capes; voice and the rest later), which travel beside the game's packets. Calls
 * come on the channel's event loop.
 */
public interface EaglerSession {

    /**
     * The server logged the player in, as {@code name} with {@code uuid} (offline-mode UUIDs unless
     * an auth plugin chose others). {@code messages} sends Eagler messages from now on.
     */
    default void loggedIn(String name, UUID uuid, EaglerMessages messages) {}

    /**
     * One Eagler message from the server: its id and body (V5 numbering, {@link EaglerMessages}).
     * The buffer is only valid during the call.
     */
    default void received(int id, ByteBuf body) {}

    /**
     * The standard capabilities the server accepted of those asked for ({@link EaglerLogin#VOICE}
     * and so on), before {@link #loggedIn}.
     */
    default void capabilities(int accepted) {}

    /**
     * The server wants a password: none was offered, the one sent was refused, or it must go
     * unhashed and the player hasn't agreed. {@code method} is the server's ({@link
     * EaglerPassword}), {@code message} its words for the player. The login then fails.
     */
    default void authenticationRequired(int method, String message) {}

    /** The server sent the player on to {@code address} instead of logging in. The login then fails. */
    default void redirected(String address) {}

    /** The connection closed. */
    default void closed() {}
}
