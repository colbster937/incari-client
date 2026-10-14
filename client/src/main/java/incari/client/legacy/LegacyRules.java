package incari.client.legacy;

import incari.client.net.ServerConnections;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

/**
 * Which of the old versions' behaviours are on (ADR 0020), from the protocol of the server
 * the player is on: set once a tick, read everywhere as plain fields. Some reads are in hot paths
 * (every collision query asks {@link #blockShapes18}), and a stale read for one tick after joining
 * does no harm, so the fields are neither volatile nor locked.
 *
 * <p>Versions, by protocol: 47 is 1.8, 340 is 1.12.2, 404 is 1.13.2.
 */
public final class LegacyRules {

    static final int V1_8 = 47;
    static final int V1_12_2 = 340;
    static final int V1_13_2 = 404;

    /** The protocol the rules follow now: the server's, or the game's own. */
    private static int protocol = -1;

    /** 1.8's combat: no cooldown, sword blocking, the swing before the attack, 0.1 pick margin. */
    static boolean combat18;
    /** 1.8's cut-off for slow movement: 0.005 on each axis. */
    static boolean movement18;
    /** Before 1.14: sneaking keeps eye height 1.54 and the box 1.8 (1.8) or 1.65 (1.9 to 1.13). */
    static boolean oldSneak;
    /** Before 1.13: no swimming or crawling pose. */
    static boolean noSwimming;
    /** 1.8's block shapes: ladders, lily pads, panes, anvils, farmland. */
    static boolean blockShapes18;

    private LegacyRules() {}

    /** {@link incari.client.IncariTicks}: follows the server the player is on. Game thread. */
    public static void update(Minecraft minecraft) {
        int now = minecraft.getConnection() == null || minecraft.isLocalServer()
                ? SharedConstants.getCurrentVersion().protocolVersion()
                : ServerConnections.lastJoinedProtocol();
        if (now == protocol) {
            return;
        }
        protocol = now;
        boolean current = now == SharedConstants.getCurrentVersion().protocolVersion() || now < 0;
        combat18 = !current && now <= V1_8;
        movement18 = !current && now <= V1_8;
        blockShapes18 = !current && now <= V1_8;
        oldSneak = !current && now <= V1_13_2;
        noSwimming = !current && now <= V1_12_2;
    }

    /** The protocol the rules follow, for the debug screen and checks. */
    public static int protocol() {
        return protocol;
    }

    /** Whether 1.8's combat is on, for checks. */
    public static boolean combat18() {
        return combat18;
    }

    /** Whether sneaking keeps 1.8's box, for checks. */
    public static boolean sneak18() {
        return oldSneak && protocol <= V1_8;
    }
}
