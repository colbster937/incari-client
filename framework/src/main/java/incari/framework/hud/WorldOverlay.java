package incari.framework.hud;

/**
 * The canvas for {@link incari.framework.event.Events#OVERLAY}: the HUD's canvas, drawn every
 * frame over the world (under the game's HUD), with a way to find where a point in the world shows
 * on screen. What follows the world (a waypoint's label) is drawn here, so it keeps up with the
 * camera at any frame rate. Render thread.
 */
public interface WorldOverlay extends HudCanvas {

    /**
     * Where the point {@code x, y, z} in the world shows on screen this frame: {@code out[0]} and
     * {@code out[1]} get its position in GUI units, {@code out[2]} its distance from the camera in
     * blocks. False if it's behind the camera: then {@code out[0], out[1]} are far off screen the
     * way to turn to it from the screen's middle (straight down for straight behind), for an arrow
     * at the screen's edge.
     */
    boolean project(double x, double y, double z, float[] out);

    /** The camera's position in the world this frame: x. */
    double cameraX();

    /** The camera's y. */
    double cameraY();

    /** The camera's z. */
    double cameraZ();
}
