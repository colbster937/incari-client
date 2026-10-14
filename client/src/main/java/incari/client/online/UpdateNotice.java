package incari.client.online;

import incari.client.IncariBuild;
import incari.framework.online.Online.Status;
import incari.online.OnlineService;
import incari.online.Releases;
import java.net.URI;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Whether the title screen tells the player a newer Incari Client is out, and leads them to its
 * repository. The online service names the latest release in its {@code hello} answer; with no
 * service, nothing is shown.
 *
 * <p>Release builds only: a dev build's version is the one being worked toward, so it would be
 * told of updates it doesn't need. Tests turn it on ({@code -Dincari.updates=on}, the page's
 * {@code ?updates=on}). Game thread.
 */
public final class UpdateNotice {

    private static boolean forced = "on".equals(System.getProperty("incari.updates"));

    private UpdateNotice() {}

    /** Before the game starts: shows the notice in a dev build too, for tests. */
    public static void force(boolean on) {
        forced = on;
    }

    /**
     * What the notice says, or null for none: the newer version when the service named one, "" when
     * the service no longer speaks this client's protocol (an update is needed, version unknown).
     */
    public static @Nullable String newer(OnlineService online) {
        if (!(IncariBuild.RELEASE || forced) || IncariBuild.REPOSITORY.isEmpty()) {
            return null;
        }
        String latest = online.latestRelease();
        if (Releases.newer(latest, IncariBuild.VERSION)) {
            return latest;
        }
        return online.status() == Status.UPDATE_NEEDED ? "" : null;
    }

    /** Asks before opening the repository, as the game asks before any link, then comes back to {@code from}. */
    public static void open(Screen from) {
        ConfirmLinkScreen.confirmLinkNow(from, URI.create(IncariBuild.REPOSITORY));
    }
}
