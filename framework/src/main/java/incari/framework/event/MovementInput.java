package incari.framework.event;

/**
 * The movement keys for one tick, as {@link Events#MOVEMENT} hands them to listeners. One object,
 * reused every tick: listeners read and change it during the call and keep no reference.
 *
 * <p>Each value starts as the game read it: the key held, or latched if the game's own toggle
 * option is on for it. The press counts are how many times the sprint and sneak keys went down
 * since the last tick, so a tap shorter than a tick still counts.
 */
public final class MovementInput {

    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sneak;
    private boolean sprint;
    private int sneakPresses;
    private int sprintPresses;
    private boolean sneakToggledByGame;
    private boolean sprintToggledByGame;
    private boolean changed;

    /**
     * The game's side: this tick's keys, before the listeners run.
     *
     * @param sneakPresses presses of the sneak key since the last tick
     * @param sprintPresses presses of the sprint key since the last tick
     */
    public void reset(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint,
            int sneakPresses,
            int sprintPresses) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
        this.sneakPresses = sneakPresses;
        this.sprintPresses = sprintPresses;
        this.sneakToggledByGame = false;
        this.sprintToggledByGame = false;
        this.changed = false;
    }

    /**
     * The game's side, after {@link #reset}: whether the game's own toggle options (Sneak: Toggle,
     * Sprint: Toggle) are on, so {@link #sneak()} and {@link #sprint()} are the game's latches.
     */
    public void gameToggles(boolean sneak, boolean sprint) {
        this.sneakToggledByGame = sneak;
        this.sprintToggledByGame = sprint;
    }

    /** Whether the game latches the sneak key itself (Sneak: Toggle in its controls). */
    public boolean sneakToggledByGame() {
        return sneakToggledByGame;
    }

    /** Whether the game latches the sprint key itself (Sprint: Toggle in its controls). */
    public boolean sprintToggledByGame() {
        return sprintToggledByGame;
    }

    /** Whether forward is held. */
    public boolean forward() {
        return forward;
    }

    /** Whether back is held. */
    public boolean backward() {
        return backward;
    }

    /** Whether strafe left is held. */
    public boolean left() {
        return left;
    }

    /** Whether strafe right is held. */
    public boolean right() {
        return right;
    }

    /** Whether jump is held. */
    public boolean jump() {
        return jump;
    }

    /** Whether the player sneaks this tick. */
    public boolean sneak() {
        return sneak;
    }

    /** Whether the player tries to sprint this tick (the game still decides whether they can). */
    public boolean sprint() {
        return sprint;
    }

    /** Presses of the sneak key since the last tick. */
    public int sneakPresses() {
        return sneakPresses;
    }

    /** Presses of the sprint key since the last tick. */
    public int sprintPresses() {
        return sprintPresses;
    }

    /** Makes the player sneak this tick, or not. */
    public void setSneak(boolean sneak) {
        if (this.sneak != sneak) {
            this.sneak = sneak;
            changed = true;
        }
    }

    /** Makes the player try to sprint this tick, or not. */
    public void setSprint(boolean sprint) {
        if (this.sprint != sprint) {
            this.sprint = sprint;
            changed = true;
        }
    }

    /** The game's side: whether any listener changed a value, so the game's copy needs replacing. */
    public boolean changed() {
        return changed;
    }
}
