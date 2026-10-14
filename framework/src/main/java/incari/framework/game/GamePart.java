package incari.framework.game;

/**
 * A part of the game's own HUD a module can take over ({@code GamePartElement}): the game stops
 * drawing it in its place, and the module's HUD element draws it wherever the player puts it.
 */
public enum GamePart {
    /** The scoreboard sidebar a server shows on the right. */
    SCOREBOARD("Scoreboard");

    private final String label;

    GamePart(String label) {
        this.label = label;
    }

    /** What the settings screen and the HUD editor call it. */
    public String label() {
        return label;
    }
}
