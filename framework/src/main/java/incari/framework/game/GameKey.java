package incari.framework.game;

/** One of the game's own keybinds, as the player has bound it ({@link Game#isDown}). */
public enum GameKey {
    FORWARD,
    LEFT,
    BACK,
    RIGHT,
    JUMP,
    SNEAK,
    SPRINT,
    /** Attack / destroy: the left mouse button by default. */
    ATTACK,
    /** Use item / place block: the right mouse button by default. */
    USE
}
