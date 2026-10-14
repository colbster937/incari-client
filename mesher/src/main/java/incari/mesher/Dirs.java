package incari.mesher;

/** The six directions as indices, in the game's order: down, up, north, south, west, east. */
final class Dirs {

    private Dirs() {}

    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    static final int[] STEP_X = {0, 0, 0, 0, -1, 1};
    static final int[] STEP_Y = {-1, 1, 0, 0, 0, 0};
    static final int[] STEP_Z = {0, 0, -1, 1, 0, 0};

    /** The horizontal directions in the game's order for them: north, east, south, west. */
    static final int[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};

    static int opposite(int direction) {
        return direction ^ 1;
    }

    /** The direction's axis as x = 0, y = 1, z = 2. */
    static int axisXyz(int direction) {
        return switch (direction) {
            case DOWN, UP -> 1;
            case NORTH, SOUTH -> 2;
            default -> 0;
        };
    }

    /** Up, south and east point along their axis; the others against it. */
    static boolean positive(int direction) {
        return (direction & 1) == 1;
    }
}
