package incari.framework.event;

/**
 * The flames drawn over the view while the player burns, as {@link Events#FIRE_OVERLAY} hands them
 * to listeners. One object, reused every frame: listeners change it during the call and keep no
 * reference.
 */
public final class FireOverlay {

    private float lower;

    /** The game's side: the flames where the game puts them, before the listeners run. */
    public void reset() {
        lower = 0;
    }

    /** How far down the flames are moved, in flame heights. */
    public float lower() {
        return lower;
    }

    /** Moves the flames down by {@code heights} of their height (0.3 for 30%). */
    public void setLower(float heights) {
        this.lower = heights;
    }
}
