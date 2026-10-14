package incari.client.net;

import incari.client.platform.ClientPlatform;
import java.io.IOException;

/**
 * The integrated server's "Open to LAN" on a platform that can't listen for connections (the
 * browser). Netty's server sockets aren't in the browser build, so publishing failed with an
 * exception the game didn't expect and the click handler logged an error. Failing with an
 * {@link IOException} instead takes the game's own path: publishing reports that it failed, and
 * the World Options screen says the LAN game couldn't be hosted.
 */
public final class LanHosting {

    private LanHosting() {}

    /** Called before the game starts listening on a TCP port; throws where it can't. */
    public static void checkCanListen() throws IOException {
        if (!ClientPlatform.get().network().canAcceptConnections()) {
            throw new IOException("This platform can't accept connections from other machines");
        }
    }
}
