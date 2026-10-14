package incari.framework.game;

/**
 * A burst of particles a module shows on what the player hit ({@link Game#effectOnTarget}): the
 * game's own particles, on this client only.
 */
public enum Effect {
    /** The game's critical hit stars. */
    CRITS("Crits"),
    /** The blue stars of an enchanted weapon's hit. */
    MAGIC("Magic"),
    /** Little electric sparks. */
    SPARKS("Sparks"),
    /** Hearts. */
    HEARTS("Hearts"),
    /** Flames. */
    FLAMES("Flames"),
    /** Snowflakes. */
    SNOW("Snow"),
    /** A firework's burst of sparkles. */
    FIREWORK("Firework"),
    /** Souls rising. */
    SOULS("Souls"),
    /** A puff of smoke. */
    SMOKE("Smoke"),
    /** A totem's green and gold. */
    TOTEM("Totem");

    private final String label;

    Effect(String label) {
        this.label = label;
    }

    /** What the settings screen calls it. */
    public String label() {
        return label;
    }
}
