package incari.framework.hud;

/**
 * Which point of the screen a HUD element is placed from. The element's matching corner, edge
 * middle or center sits there, moved inward by its offsets, so it stays put in its corner however
 * the window or the GUI scale changes.
 */
public enum Anchor {
    TOP_LEFT(0, 0, "Top left"),
    TOP(1, 0, "Top"),
    TOP_RIGHT(2, 0, "Top right"),
    LEFT(0, 1, "Left"),
    CENTER(1, 1, "Center"),
    RIGHT(2, 1, "Right"),
    BOTTOM_LEFT(0, 2, "Bottom left"),
    BOTTOM(1, 2, "Bottom"),
    BOTTOM_RIGHT(2, 2, "Bottom right");

    /** 0 left, 1 center, 2 right. */
    private final int column;
    /** 0 top, 1 middle, 2 bottom. */
    private final int row;

    private final String label;

    Anchor(int column, int row, String label) {
        this.column = column;
        this.row = row;
        this.label = label;
    }

    /** What the settings screen calls it. */
    public String label() {
        return label;
    }

    /** Across the screen: 0 left, 1 the middle, 2 right. */
    public int column() {
        return column;
    }

    /** Down the screen: 0 top, 1 the middle, 2 bottom. */
    public int row() {
        return row;
    }

    /**
     * Where an element starts across the screen.
     *
     * @param offset the element's offset: inward from a side, rightward from the middle
     * @param size the element's width, scaled
     * @param screen the screen's width
     * @return the element's left edge, kept on screen
     */
    public int x(int offset, int size, int screen) {
        return place(column, offset, size, screen);
    }

    /** Where an element starts down the screen; {@link #x}'s rules, top to bottom. */
    public int y(int offset, int size, int screen) {
        return place(row, offset, size, screen);
    }

    /** The offset across that puts an element's left edge at {@code start}: {@link #x}'s inverse. */
    public int offsetX(int start, int size, int screen) {
        return offset(column, start, size, screen);
    }

    /** The offset down that puts an element's top edge at {@code start}: {@link #y}'s inverse. */
    public int offsetY(int start, int size, int screen) {
        return offset(row, start, size, screen);
    }

    /**
     * The anchor for an element placed at {@code x, y}: the screen's corner, edge or middle in
     * whose third its middle lies, so it keeps its place from the nearest side as the window
     * changes.
     */
    public static Anchor nearest(int x, int y, int width, int height, int screenWidth, int screenHeight) {
        int column = third(x * 2 + width, screenWidth * 2);
        int row = third(y * 2 + height, screenHeight * 2);
        for (Anchor anchor : values()) {
            if (anchor.column == column && anchor.row == row) {
                return anchor;
            }
        }
        throw new AssertionError("No anchor at " + column + ", " + row);
    }

    /** Which third of {@code size} {@code at} is in: 0, 1 or 2. */
    private static int third(int at, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(2, at * 3 / size));
    }

    private static int offset(int third, int start, int size, int screen) {
        return switch (third) {
            case 0 -> start;
            case 1 -> start - (screen - size) / 2;
            default -> screen - size - start;
        };
    }

    private static int place(int third, int offset, int size, int screen) {
        int start =
                switch (third) {
                    case 0 -> offset;
                    case 1 -> (screen - size) / 2 + offset;
                    default -> screen - size - offset;
                };
        // An element bigger than the screen stays at its start rather than off the far side.
        return Math.max(0, Math.min(start, screen - size));
    }
}
