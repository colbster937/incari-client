package incari.framework.game;

/** What the sky does, as the client may show it ({@link Game#overrideSky}). */
public enum Weather {
    CLEAR("Clear"),
    RAIN("Rain"),
    THUNDER("Thunder"),
    /** Snow wherever it falls, whatever the biome. */
    SNOW("Snow");

    private final String label;

    Weather(String label) {
        this.label = label;
    }

    /** What the settings screen calls it. */
    public String label() {
        return label;
    }
}
