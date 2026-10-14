package incari.platform.desktop.net;

import incari.platform.api.PlatformLog;
import incari.platform.api.net.NetConnection;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * One WebSocket through the JDK's HTTP client. Each send is one binary message (or one text
 * message, for Eagler's queries); the JDK may hand a large incoming message over in pieces, so
 * they're joined before the listener sees it.
 *
 * <p>The JDK allows one outstanding send per socket, so sends are chained: each starts when the one
 * before it has been written. Listener callbacks come on the HTTP client's threads, one at a time.
 */
// Each future is finished in its own whenComplete stage, which reports failures to the listener.
@SuppressWarnings("FutureReturnValueIgnored")
final class WebSocketConnection implements NetConnection {

    /**
     * Larger than any packet the game accepts (a 21-bit frame length), with room for Eagler's
     * extras. A server sending more is broken or hostile; the connection fails rather than grow.
     */
    static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final String url;
    private final NetConnection.Listener listener;
    private final PlatformLog log;
    private final Object lock = new Object();
    /** Sends made before the socket opened, written in order once it does. */
    private final Queue<Object> pending = new ArrayDeque<>();

    private @Nullable WebSocket socket;
    /** Completes when the last send queued so far has been written. */
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);

    private boolean open;
    private boolean closed;
    private boolean finished;

    WebSocketConnection(String url, NetConnection.Listener listener, PlatformLog log) {
        this.url = url;
        this.listener = listener;
        this.log = log;
    }

    void start(HttpClient client) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            finish(new IOException("Not a WebSocket URL: " + url, e));
            return;
        }
        client.newWebSocketBuilder().buildAsync(uri, new Receiver()).whenComplete((opened, failure) -> {
            if (failure != null) {
                finish(unwrap(failure));
            } else {
                onOpened(opened);
            }
        });
    }

    private void onOpened(WebSocket opened) {
        synchronized (lock) {
            if (closed) {
                opened.abort();
                return;
            }
            socket = opened;
            while (!pending.isEmpty()) {
                sendLocked(opened, pending.remove());
            }
            open = true;
        }
        listener.connected();
    }

    @Override
    public void send(ByteBuffer data) {
        enqueue(data);
    }

    @Override
    public void sendText(String text) {
        enqueue(text);
    }

    /** A {@link ByteBuffer} (binary message) or a {@link String} (text message). */
    private void enqueue(Object data) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            WebSocket current = socket;
            if (current == null) {
                pending.add(data);
            } else {
                sendLocked(current, data);
            }
        }
    }

    private void sendLocked(WebSocket target, Object data) {
        lastSend = lastSend.thenCompose(ignored -> data instanceof String text
                        ? target.sendText(text, true)
                        : target.sendBinary((ByteBuffer) data, true))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        log.debug("Send to " + url + " failed: " + failure);
                        target.abort();
                        finish(unwrap(failure));
                    }
                });
    }

    @Override
    public void close() {
        WebSocket current;
        CompletableFuture<?> sends;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            open = false;
            current = socket;
            sends = lastSend;
            pending.clear();
        }
        if (current != null) {
            // Let queued packets (a disconnect reason) go out first, then say goodbye; a server that
            // doesn't answer the close in time is cut off.
            sends.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> current.sendClose(WebSocket.NORMAL_CLOSURE, ""))
                    .orTimeout(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((ignored, failure) -> current.abort());
        }
        finish(null);
    }

    @Override
    public boolean isOpen() {
        synchronized (lock) {
            return open;
        }
    }

    @Override
    public String remoteDescription() {
        return url;
    }

    // Reports the end once, whichever of open, receive, send or close gets there first.
    private void finish(@Nullable Throwable cause) {
        synchronized (lock) {
            closed = true;
            open = false;
            if (finished) {
                return;
            }
            finished = true;
        }
        listener.closed(cause);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    /** The JDK's callbacks: one message at a time, asking for the next only when this one is handled. */
    private final class Receiver implements WebSocket.Listener {

        /** Pieces of the message being received; empty between messages. */
        private final List<ByteBuffer> pieces = new ArrayList<>();

        /** The text message being received; empty between messages. */
        private final StringBuilder text = new StringBuilder();

        private int size;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public @Nullable CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            size += data.remaining();
            if (size > MAX_MESSAGE_BYTES) {
                webSocket.abort();
                finish(new ProtocolException("A message from " + url + " is over " + MAX_MESSAGE_BYTES + " bytes"));
                return null;
            }
            // The JDK may reuse the buffer once this returns, so keep a copy.
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data).flip();
            pieces.add(copy);
            if (last) {
                ByteBuffer message = join();
                if (!isFinished()) {
                    listener.received(message);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // Game packets are binary; Eagler servers answer queries (the MOTD) in text.
            size += data.length();
            if (size > MAX_MESSAGE_BYTES) {
                webSocket.abort();
                finish(new ProtocolException("A message from " + url + " is over " + MAX_MESSAGE_BYTES + " bytes"));
                return null;
            }
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                size = 0;
                if (!isFinished()) {
                    listener.receivedText(message);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode == WebSocket.NORMAL_CLOSURE || statusCode == 1001) {
                finish(null);
            } else {
                finish(new IOException("The server closed the connection (" + statusCode
                        + (reason.isEmpty() ? "" : ": " + reason) + ")"));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            finish(error);
        }

        private ByteBuffer join() {
            ByteBuffer message;
            if (pieces.size() == 1) {
                message = pieces.get(0);
            } else {
                message = ByteBuffer.allocate(size);
                for (ByteBuffer piece : pieces) {
                    message.put(piece);
                }
                message.flip();
            }
            pieces.clear();
            size = 0;
            return message;
        }
    }

    private boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }
}
