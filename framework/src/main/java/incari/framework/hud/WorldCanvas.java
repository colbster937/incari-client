package incari.framework.hud;

/**
 * What {@link incari.framework.event.Events#WORLD}'s listeners draw into the world itself, with
 * its blocks in front of and behind it. Render thread, while the frame's world is put together.
 */
public interface WorldCanvas {

    /**
     * A beacon's beam in {@code rgb} up from {@code x, y, z} (the block's corner, as a beacon's
     * block position) into the sky. A beam farther than the world is drawn is moved in along the
     * line of sight to where it's drawn, looking the same: the player sees it from any distance.
     */
    void beam(int x, int y, int z, int rgb);

    /** The camera's position in the world this frame: x. */
    double cameraX();

    /** The camera's y. */
    double cameraY();

    /** The camera's z. */
    double cameraZ();
}
