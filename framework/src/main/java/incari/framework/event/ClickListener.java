package incari.framework.event;

/** Receives {@link Events#CLICK}. */
@FunctionalInterface
public interface ClickListener {

    /** The left mouse button. */
    int LEFT = 0;

    /** The right mouse button. */
    int RIGHT = 1;

    /** The middle button (the wheel). */
    int MIDDLE = 2;

    /**
     * A mouse button went down over the game, with no screen open.
     *
     * @param button {@link #LEFT}, {@link #RIGHT}, {@link #MIDDLE} or a side button (3 up)
     */
    void onClick(int button);
}
