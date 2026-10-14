package incari.client;

/**
 * Catch-up ticks spread over frames (ADR 0032). After a slow frame the game runs every tick it
 * missed back to back, up to ten, in the next frame, which makes that frame slow as well: on the
 * floor stand-in the slowest 1% of frames were mostly these runs. Here the ticks due are owed
 * instead, and each frame runs as many as the frame rate needs on average, rounded up, so the debt
 * drains a tick a frame over the next few frames. The game still runs every tick it would have (up to
 * the ten it would run at once; it drops the rest, and so does this), a few frames later.
 *
 * <p>While the debt drains the game runs a little behind the clock: entities move a tick a frame
 * until it's paid. A debt of more than two frames' worth runs one tick more a frame. The debt never
 * exceeds the ten ticks the game would run at once, so after a long stall no more ticks run than the
 * game would run (an earlier version kept a second's worth, and after the stalls of a busy fight
 * ran twice the game's ticks, each a heavy one); bursts that come back to back can then drop a few
 * ticks the game would have run. Off unless the page turns it on ({@code ?ticks=game} leaves it off, to compare); the
 * desktop runs the game's way. Game thread.
 */
public final class TickPacing {

    /** The most ticks the game runs in one frame; it drops any more. */
    private static final int GAME_LIMIT = 10;

    /** The weight of each frame in the average of ticks due a frame. */
    private static final float SMOOTHING = 1.0F / 16.0F;

    private static boolean enabled;

    private static int debt;

    // Ticks due a frame, averaged: one at 20 fps, a third at 60.
    private static float average = 1.0F;

    private TickPacing() {}

    public static void setEnabled(boolean on) {
        enabled = on;
        debt = 0;
        average = 1.0F;
    }

    /**
     * Hook ({@code Minecraft.runTick}, after the ticks due are worked out): how many of the ticks due
     * to run this frame. {@code advancing} is whether game time moves at all; while it doesn't, the
     * debt waits.
     */
    public static int ticksToRun(int due, boolean advancing) {
        if (!enabled) {
            return due;
        }
        if (!advancing) {
            return 0;
        }
        average += (due - average) * SMOOTHING;
        debt = Math.min(GAME_LIMIT, debt + Math.min(GAME_LIMIT, due));
        int limit = Math.max(1, (int) Math.ceil(average));
        if (debt > 2 * limit) {
            limit++;
        }
        int run = Math.min(debt, limit);
        debt -= run;
        return run;
    }

    /** The ticks owed to later frames. */
    public static int debt() {
        return debt;
    }
}
