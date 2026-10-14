package incari.platform.web;

/**
 * The canvas's size as the game sees it.
 *
 * @param width the CSS width: the window's width in screen coordinates, which mouse positions
 *     are relative to
 * @param pixelWidth the backing store's width in device pixels: the framebuffer
 * @param density device pixels per CSS pixel ({@code devicePixelRatio}), which browser zoom
 *     changes too
 */
record CanvasSize(int width, int height, int pixelWidth, int pixelHeight, double density) {

    /**
     * The size from one measurement of the page.
     *
     * @param cssWidth the CSS content width, possibly fractional
     * @param deviceWidth the exact device-pixel width where the browser reports one
     *     ({@code devicePixelContentBoxSize}), else 0 to derive it from the CSS size and density
     * @param previous the size measured before: kept when the page reports an empty canvas (it
     *     was hidden or isn't laid out), and its exact pixel size kept when a measurement without
     *     one agrees with it
     */
    static CanvasSize measure(
            double cssWidth,
            double cssHeight,
            double deviceWidth,
            double deviceHeight,
            double density,
            CanvasSize previous) {
        if (!(cssWidth > 0 && cssHeight > 0)) {
            return previous;
        }
        double scale = density > 0 ? density : 1;
        int width = Math.max(1, (int) Math.round(cssWidth));
        int height = Math.max(1, (int) Math.round(cssHeight));
        int pixelWidth;
        int pixelHeight;
        if (deviceWidth > 0 && deviceHeight > 0) {
            pixelWidth = (int) Math.round(deviceWidth);
            pixelHeight = (int) Math.round(deviceHeight);
        } else if (width == previous.width && height == previous.height && scale == previous.density) {
            // A window resize or density check that found nothing new: keep the exact size the
            // ResizeObserver reported, rather than a rounded guess that can be off by a pixel.
            pixelWidth = previous.pixelWidth;
            pixelHeight = previous.pixelHeight;
        } else {
            pixelWidth = (int) Math.round(cssWidth * scale);
            pixelHeight = (int) Math.round(cssHeight * scale);
        }
        return new CanvasSize(width, height, Math.max(1, pixelWidth), Math.max(1, pixelHeight), scale);
    }

    /** The size to start from before the page has measured the canvas. */
    static CanvasSize initial(double cssWidth, double cssHeight, double density) {
        CanvasSize unknown = new CanvasSize(1, 1, 1, 1, density > 0 ? density : 1);
        return measure(cssWidth, cssHeight, 0, 0, density, unknown);
    }
}
