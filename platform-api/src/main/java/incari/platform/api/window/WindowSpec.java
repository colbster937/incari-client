package incari.platform.api.window;

/**
 * How to create the window.
 *
 * @param title the initial title
 * @param width the initial width in screen coordinates, at least {@link #MIN_WIDTH}
 * @param height the initial height in screen coordinates, at least {@link #MIN_HEIGHT}
 * @param maximumSize the largest the window may be in either dimension, or 0 for no limit
 */
public record WindowSpec(String title, int width, int height, int maximumSize) {

    /** The smallest window the game's UI fits in. */
    public static final int MIN_WIDTH = 320;

    public static final int MIN_HEIGHT = 240;

    public WindowSpec {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            throw new IllegalArgumentException(
                    "Window " + width + "x" + height + " is smaller than the minimum " + MIN_WIDTH + "x" + MIN_HEIGHT);
        }
    }
}
