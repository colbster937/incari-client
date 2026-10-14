package incari.framework.hud;

import java.util.List;

/**
 * A small filled shape the canvas draws smooth at any size ({@link HudCanvas#shape}): a waypoint's
 * icon, a marker on the minimap, the player's arrow. Each is drawn with a thin dark rim so it reads
 * over any terrain or sky.
 */
public enum Shape {
    CIRCLE("Circle"),
    SQUARE("Square"),
    DIAMOND("Diamond"),
    TRIANGLE("Triangle"),
    STAR("Star"),
    HEART("Heart"),
    HEXAGON("Hexagon"),
    CROSS("Cross"),
    /** A pointer, its tip up at rotation 0: the player on the minimap, an arrow to what's off screen. */
    ARROW("Arrow");

    /** The shapes a waypoint can take, in the order the editor offers them. */
    public static final List<Shape> MARKERS = List.of(CIRCLE, SQUARE, DIAMOND, TRIANGLE, STAR, HEART, HEXAGON, CROSS);

    private final String label;

    Shape(String label) {
        this.label = label;
    }

    /** What the editor calls it. */
    public String label() {
        return label;
    }
}
