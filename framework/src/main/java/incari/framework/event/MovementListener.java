package incari.framework.event;

/** Receives {@link Events#MOVEMENT}. */
@FunctionalInterface
public interface MovementListener {

    /**
     * The movement keys have been read for this tick. Hot path: don't allocate.
     *
     * @param input what the keys say; changes go to the player's movement
     */
    void onMovementInput(MovementInput input);
}
