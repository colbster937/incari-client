package incari.client.net;

import incari.client.eagler.EaglerJoinFailures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * {@code DisconnectedScreen.init}: a join that failed in a way Incari can act on is taken up here
 * instead of showing the reason. In order: an Eagler server's redirect or request for a password
 * (ADR 0019), then a server refusing an "Auto" join's version (ADR 0018). Render thread.
 */
public final class JoinFailures {

    private JoinFailures() {}

    /** Returns true if the failure was taken up and the disconnection screen must show nothing. */
    public static boolean handle(Minecraft minecraft, Screen parent, Component reason) {
        ServerConnections.Join join = ServerConnections.lastJoin();
        if (join != null && EaglerJoinFailures.handle(minecraft, parent, join.server(), join.address())) {
            return true;
        }
        return VersionRetry.retry(minecraft, parent, reason);
    }
}
