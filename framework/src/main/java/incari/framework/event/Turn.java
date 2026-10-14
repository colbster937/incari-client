package incari.framework.event;

/**
 * The mouse turning the view, as {@link Events#TURN} hands it to listeners, in degrees after the
 * player's sensitivity and inversion. A listener that takes the turn ({@link #consume()}) keeps it
 * from the player: Freelook turns the camera instead. One object, reused.
 */
public final class Turn {

    private float yaw;
    private float pitch;
    private boolean consumed;

    /** The game's side: this turn, before the listeners run. */
    public void reset(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.consumed = false;
    }

    /** Degrees to the right. */
    public float yaw() {
        return yaw;
    }

    /** Degrees down. */
    public float pitch() {
        return pitch;
    }

    /** Keeps the turn from the player. */
    public void consume() {
        consumed = true;
    }

    /** Whether a listener took it. */
    public boolean consumed() {
        return consumed;
    }
}
