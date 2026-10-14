package incari.framework;

/** Where a module is listed in the module menu, in this order. */
public enum Category {
    /** Information drawn on the HUD: keystrokes, FPS, coordinates, armor. */
    HUD("HUD"),
    /** Combat: displays and the feel of fighting. */
    COMBAT("Combat"),
    /** How the player moves: toggle sprint, toggle sneak. */
    MOVEMENT("Movement"),
    /** How the world looks: zoom, freelook, the crosshair, presets. */
    VISUAL("Visual"),
    /** Everything else: chat, screenshots, waypoints. */
    UTILITY("Utility");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    /** What the module menu calls it. */
    public String label() {
        return label;
    }
}
