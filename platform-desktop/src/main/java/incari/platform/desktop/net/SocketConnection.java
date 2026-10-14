package incari.platform.desktop.net;

import incari.platform.api.PlatformLog;
import incari.platform.api.net.NetConnection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import org.jspecify.annotations.Nullable;

/**
 * One TCP connection. A virtual thread connects and then reads; writes happen on the caller's
 * thread under a lock, so {@link #send} never waits for the network to become readable.
 */
final class SocketConnection implements NetConnection {

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final String host;
    private final int port;
    private final NetConnection.Listener listener;
    private final PlatformLog log;
    private final Object lock = new Object();
    /** Sends made before the connection opened, written in order once it does. */
    private final Queue<ByteBuffer> pending = new ArrayDeque<>();

    private @Nullable SocketChannel channel;
    private boolean open;
    private boolean closed;
    /** Set by the connecting thread once resolved; read from any thread. */
    private volatile String remote;

    SocketConnection(String host, int port, NetConnection.Listener listener, PlatformLog log) {
        this.host = host;
        this.port = port;
        this.listener = listener;
        this.log = log;
        this.remote = host + ":" + port;
    }

    void start() {
        Thread.ofVirtual().name("Incari-connection-" + remote).start(this::run);
    }

    private void run() {
        SocketChannel socket;
        try {
            socket = SocketChannel.open();
            socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
            InetSocketAddress address = new InetSocketAddress(host, port);
            socket.socket().connect(address, CONNECT_TIMEOUT_MS);
            remote = String.valueOf(socket.getRemoteAddress());
        } catch (IOException | RuntimeException e) {
            finish(e);
            return;
        }
        IOException flushFailure = null;
        synchronized (lock) {
            if (closed) {
                closeQuietly(socket);
                return;
            }
            channel = socket;
            try {
                while (!pending.isEmpty()) {
                    writeFully(socket, pending.remove());
                }
                open = true;
            } catch (IOException e) {
                closed = true;
                closeQuietly(socket);
                flushFailure = e;
            }
        }
        if (flushFailure != null) {
            finishOutsideLock(flushFailure);
            return;
        }
        listener.connected();
        readLoop(socket);
    }

    private void readLoop(SocketChannel socket) {
        try {
            while (true) {
                ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
                int read = socket.read(buffer);
                if (read < 0) {
                    finish(null);
                    return;
                }
                if (read > 0) {
                    buffer.flip();
                    listener.received(buffer);
                }
            }
        } catch (IOException e) {
            finish(isClosed() ? null : e);
        }
    }

    @Override
    public void send(ByteBuffer data) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            SocketChannel socket = channel;
            if (socket == null) {
                pending.add(data);
                return;
            }
            try {
                writeFully(socket, data);
            } catch (IOException e) {
                log.debug("Write to " + remote + " failed: " + e);
                closed = true;
                open = false;
                closeQuietly(socket);
                // The read loop sees the closed socket and reports it to the listener.
            }
        }
    }

    private static void writeFully(SocketChannel socket, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            socket.write(data);
        }
    }

    @Override
    public void close() {
        SocketChannel socket;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            open = false;
            socket = channel;
        }
        if (socket != null) {
            // Unblocks the read loop, which then reports the orderly close.
            closeQuietly(socket);
        } else {
            finish(null);
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Reports the end once, whichever of connect, read, write or close gets there first. */
    private final Object finishLock = new Object();

    private boolean finished;

    private void finish(@Nullable Throwable cause) {
        synchronized (lock) {
            closed = true;
            open = false;
            SocketChannel socket = channel;
            if (socket != null) {
                closeQuietly(socket);
            }
        }
        finishOutsideLock(cause);
    }

    private void finishOutsideLock(@Nullable Throwable cause) {
        synchronized (finishLock) {
            if (finished) {
                return;
            }
            finished = true;
        }
        listener.closed(cause);
    }

    private void closeQuietly(SocketChannel socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("Closing " + remote + " failed: " + e);
        }
    }

    @Override
    public boolean isOpen() {
        synchronized (lock) {
            return open;
        }
    }

    @Override
    public String remoteDescription() {
        return remote;
    }
}
