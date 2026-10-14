package incari.framework.game;

/**
 * A game option a module may hold while it's on ({@link incari.framework.Module#holdOption}): the
 * option's own control shows the module's value, and changing it gives control back to the player.
 * A slider's is a fraction from 0 to 1; a choice's is its index.
 */
public enum GameOption {
    /** Chat Settings' Text Background Opacity: the dark box behind chat lines. */
    TEXT_BACKGROUND_OPACITY("Text Background Opacity", 0.5),
    /** Accessibility's Damage Tilt: how far the camera shakes when the player is hurt. */
    DAMAGE_TILT("Damage Tilt", 1.0),
    /** Video Settings' Attack Indicator: 0 Off, 1 Crosshair, 2 Hotbar. */
    ATTACK_INDICATOR("Attack Indicator", 1.0, 2.0);

    private final String label;
    private final double defaultValue;
    private final double max;

    GameOption(String label, double defaultValue) {
        this(label, defaultValue, 1.0);
    }

    GameOption(String label, double defaultValue, double max) {
        this.label = label;
        this.defaultValue = defaultValue;
        this.max = max;
    }

    /** The largest value: 1 for a slider, the last index for a choice. */
    public double max() {
        return max;
    }

    /** What the game's options screen calls it. */
    public String label() {
        return label;
    }

    /** The game's default, put back when a module lets go and the player's own value is unknown. */
    public double defaultValue() {
        return defaultValue;
    }
}
