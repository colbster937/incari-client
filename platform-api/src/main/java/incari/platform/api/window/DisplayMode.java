package incari.platform.api.window;

/**
 * A resolution and refresh rate a display can run at.
 *
 * @param width the width in pixels
 * @param height the height in pixels
 * @param redBits bits per pixel of red; 8 on anything the game supports
 * @param greenBits bits per pixel of green
 * @param blueBits bits per pixel of blue
 * @param refreshRate the refresh rate in hertz, for example 59.94; 0 if unknown
 */
public record DisplayMode(int width, int height, int redBits, int greenBits, int blueBits, float refreshRate) {}
