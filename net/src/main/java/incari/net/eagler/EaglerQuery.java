package incari.net.eagler;

import incari.platform.api.net.NetConnection;
import incari.platform.api.net.PlatformNetwork;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * An Eagler server's MOTD, the server list's ping for a WebSocket server. Eagler servers don't
 * answer the vanilla status ping; a WebSocket whose first message is the text {@code Accept: motd}
 * gets a JSON answer in text (the MOTD lines, player counts, a sample of names), then the server's
 * icon as a binary message if it has one: 64x64 pixels, four bytes each (red, green, blue, alpha).
 */
public final class EaglerQuery implements NetConnection.Listener {

    /** The icon's size in bytes. */
    public static final int ICON_BYTES = 64 * 64 * 4;

    /** Hears how a query ended. Calls come on a platform thread; exactly one of them, once. */
    public interface Callback {

        /**
         * The server answered.
         *
         * @param json the answer as the server sent it (its {@code data} object holds the MOTD)
         * @param icon the icon's RGBA bytes, or null if the server has none
         * @param latencyMillis from the socket opening to the answer
         */
        void answered(String json, byte @Nullable [] icon, long latencyMillis);

        /** The query failed: the server refused it, closed, or isn't an Eagler server. */
        void failed(String reason);
    }

    private final Callback callback;
    private final Clock clock;
    private final Object lock = new Object();

    private @Nullable NetConnection connection;
    private @Nullable String json;
    private long openedAt;
    private long latency;
    private boolean expectingIcon;
    private boolean done;

    /** A monotonic clock in milliseconds. */
    public interface Clock {
        long millis();
    }

    private EaglerQuery(Callback callback, Clock clock) {
        this.callback = callback;
        this.clock = clock;
    }

    /** Opens a WebSocket to {@code url} and asks for the MOTD. Returns at once. */
    public static EaglerQuery motd(PlatformNetwork network, String url, Clock clock, Callback callback) {
        EaglerQuery query = new EaglerQuery(callback, clock);
        NetConnection opened = network.openWebSocket(url, query);
        synchronized (query.lock) {
            query.connection = opened;
        }
        // Queued by the platform until the socket opens.
        opened.sendText("Accept: motd");
        return query;
    }

    /** Gives up on the query (the server list closed); the callback isn't called. */
    public void cancel() {
        NetConnection current;
        synchronized (lock) {
            done = true;
            current = connection;
        }
        if (current != null) {
            current.close();
        }
    }

    @Override
    public void connected() {
        synchronized (lock) {
            openedAt = clock.millis();
        }
    }

    @Override
    public void receivedText(String text) {
        boolean finished;
        synchronized (lock) {
            if (done || json != null) {
                return;
            }
            json = text;
            latency = clock.millis() - openedAt;
            // Whether an icon follows is in the answer; a quick look saves parsing JSON here.
            expectingIcon = text.contains("\"icon\":true");
            finished = !expectingIcon;
        }
        if (finished) {
            finish(text, null);
        }
    }

    @Override
    public void received(ByteBuffer data) {
        String answer;
        synchronized (lock) {
            if (done || json == null || !expectingIcon) {
                return;
            }
            answer = json;
        }
        byte[] icon = null;
        if (data.remaining() == ICON_BYTES) {
            icon = new byte[ICON_BYTES];
            data.get(icon);
        }
        finish(answer, icon);
    }

    @Override
    public void closed(@Nullable Throwable cause) {
        String answer;
        synchronized (lock) {
            if (done) {
                return;
            }
            answer = json;
        }
        if (answer != null) {
            // Answered, but the icon never came: the MOTD alone will do.
            finish(answer, null);
            return;
        }
        synchronized (lock) {
            done = true;
        }
        callback.failed(
                cause != null && cause.getMessage() != null
                        ? cause.getMessage()
                        : "The server closed the connection without answering");
    }

    private void finish(String answer, byte @Nullable [] icon) {
        NetConnection current;
        long took;
        synchronized (lock) {
            if (done) {
                return;
            }
            done = true;
            current = connection;
            took = latency;
        }
        if (current != null) {
            current.close();
        }
        callback.answered(answer, icon, took);
    }
}
