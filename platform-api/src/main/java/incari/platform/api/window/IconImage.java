package incari.platform.api.window;

/** One size of the window icon: RGBA pixels, row by row from the top. */
public final class IconImage {

    private final int width;
    private final int height;
    private final byte[] rgba;

    /**
     * @param rgba four bytes per pixel in R, G, B, A order; the icon takes ownership, so the
     *     caller must not change the array afterwards
     */
    public IconImage(int width, int height, byte[] rgba) {
        if (width <= 0 || height <= 0 || rgba.length != width * height * 4) {
            throw new IllegalArgumentException(
                    "A " + width + "x" + height + " icon needs " + width * height * 4 + " bytes, got " + rgba.length);
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The pixels. Shared, not copied: read them, never change them. */
    public byte[] rgba() {
        return rgba;
    }
}
