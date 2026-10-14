package incari.framework.event;

/**
 * Which way the camera looks this frame, as {@link Events#CAMERA} hands it to listeners: the
 * player's view, which a listener may turn ({@link #set}) without turning the player. Degrees,
 * the game's: yaw 0 faces south and grows toward the west; pitch 90 looks straight down. One object,
 * reused every frame.
 */
public final class CameraAngles {

    private float yaw;
    private float pitch;
    private boolean changed;

    /** The game's side: the player's view, before the listeners run. */
    public void reset(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.changed = false;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /** Turns the camera to {@code yaw}, {@code pitch}; the player keeps facing their way. */
    public void set(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.changed = true;
    }

    /** Whether a listener turned it. */
    public boolean changed() {
        return changed;
    }
}
