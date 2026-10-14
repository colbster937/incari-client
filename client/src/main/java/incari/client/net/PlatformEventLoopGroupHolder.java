package incari.client.net;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * The game's remote transport, routed through the platform: client connections (joining, the
 * server list's pings) use {@link PlatformSocketChannel}. On the desktop, "Open to LAN" still
 * listens with Netty's NIO server channel, so the event loops stay NIO ones; the platform channel
 * runs on any loop. A platform without sockets (the browser) has no selector to wait on, and its
 * entry point asks for local event loops instead ({@link #useLocalEventLoops}).
 */
public final class PlatformEventLoopGroupHolder extends EventLoopGroupHolder {

    public static final PlatformEventLoopGroupHolder INSTANCE = new PlatformEventLoopGroupHolder();

    private static volatile boolean localEventLoops;

    private PlatformEventLoopGroupHolder() {
        super("Platform", PlatformSocketChannel.class, NioServerSocketChannel.class);
    }

    /**
     * Whether {@code EventLoopGroupHolder.remote} hands out this holder. Always, on every platform:
     * the vendor code after the hook (native epoll/kqueue transports) stays only so the patch
     * doesn't have to delete it.
     */
    public static boolean routesRemoteConnections() {
        return true;
    }

    /**
     * Makes the event loops local ones, which wait for tasks rather than for sockets. Called by an
     * entry point whose platform has no sockets, before the first connection. Nothing can listen
     * for LAN connections then.
     */
    public static void useLocalEventLoops() {
        localEventLoops = true;
    }

    @Override
    protected IoHandlerFactory ioHandlerFactory() {
        return localEventLoops ? LocalIoHandler.newFactory() : NioIoHandler.newFactory();
    }
}
