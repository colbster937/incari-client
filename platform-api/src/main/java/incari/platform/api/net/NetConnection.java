package incari.platform.api.net;

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * One connection to a server, carrying bytes in order both ways. Methods may be called from any
 * thread.
 */
public interface NetConnection {

    /**
     * Sends bytes, from the buffer's position to its limit: over a WebSocket, as one binary
     * message. Queues them if the connection is still opening; does nothing once it's closed. The
     * caller must not change the buffer afterwards.
     */
    void send(ByteBuffer data);

    /**
     * Sends one text message over a WebSocket, queued like {@link #send}. Only Eagler's server
     * queries (the server list's MOTD) use text; the game's packets are always binary.
     *
     * @throws UnsupportedOperationException on a TCP connection, which has no messages
     */
    default void sendText(String text) {
        throw new UnsupportedOperationException("Text messages need a WebSocket: " + remoteDescription());
    }

    /**
     * The end of a batch of {@link #send} calls (the game's network channel was flushed): a
     * connection that gathers what it sends may send it now (singleplayer's link to its server in a
     * worker, ADR 0041). Connections that send each call at once ignore it.
     */
    default void flush() {}

    /** Closes the connection. The listener hears {@link Listener#closed} once, if it hasn't already. */
    void close();

    /** Whether the connection is established and not yet closed. */
    boolean isOpen();

    /** A description of the far end for logs, for example "mc.example.com/203.0.113.7:25565". */
    String remoteDescription();

    /**
     * Hears what happens on a connection. Callbacks come on a platform thread (the desktop's
     * reader thread, the browser's main thread), one at a time and in order.
     */
    interface Listener {

        /** The connection is established; {@link NetConnection#send} now writes to the wire. */
        void connected();

        /**
         * Bytes arrived: a piece of the stream over TCP, one whole message over a WebSocket. The
         * buffer is the listener's to keep; it's never reused by the platform.
         */
        void received(ByteBuffer data);

        /**
         * One whole text message arrived over a WebSocket. Ignored unless the listener asks for
         * text: game connections are binary, and Eagler servers answer queries in text.
         */
        default void receivedText(String text) {}

        /**
         * The connection closed or failed to open. Called exactly once, and last.
         *
         * @param cause why, or null for an orderly close by either side
         */
        void closed(@Nullable Throwable cause);
    }
}
