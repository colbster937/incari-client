package incari.classlib.image;

/**
 * Resizes 8-bit images, behind the substitute for LWJGL's {@code STBImageResize}: what the game
 * uses it for is shrinking a frame to a world's 64x64 icon. Each output pixel is the average of the
 * source area it covers, weighted by how much of each source pixel falls inside it, one axis after
 * the other; growing an image repeats source pixels. Values are averaged as they are, with no
 * colour-space conversion (STB's "linear" entry point). With an alpha channel, colours are weighted
 * by their alpha, so fully transparent pixels don't bleed their colour into their neighbours.
 */
public final class ImageResize {

    private ImageResize() {}

    /**
     * Resizes {@code source}, packed rows of {@code components} bytes a pixel, to {@code width} by
     * {@code height}.
     *
     * @param components 1 to 4 channels a pixel
     * @param alpha whether the last channel is alpha, weighting the others
     */
    public static byte[] resize(
            byte[] source, int sourceWidth, int sourceHeight, int components, boolean alpha, int width, int height) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Empty image");
        }
        if (components < 1 || components > 4) {
            throw new IllegalArgumentException("Unsupported channel count: " + components);
        }
        // Colours premultiplied by alpha, in floats, for both passes.
        float[] pixels = new float[sourceWidth * sourceHeight * components];
        for (int p = 0; p < sourceWidth * sourceHeight; p++) {
            int base = p * components;
            float weight = alpha ? (source[base + components - 1] & 0xFF) / 255f : 1f;
            for (int c = 0; c < components; c++) {
                float value = source[base + c] & 0xFF;
                pixels[base + c] = alpha && c < components - 1 ? value * weight : value;
            }
        }
        float[] wide = resizeRows(pixels, sourceWidth, sourceHeight, components, width);
        float[] done = resizeColumns(wide, width, sourceHeight, components, height);
        byte[] result = new byte[width * height * components];
        for (int p = 0; p < width * height; p++) {
            int base = p * components;
            float weight = alpha ? done[base + components - 1] / 255f : 1f;
            for (int c = 0; c < components; c++) {
                float value = done[base + c];
                if (alpha && c < components - 1) {
                    value = weight > 0 ? value / weight : 0;
                }
                result[base + c] = (byte) Math.clamp(Math.round(value), 0, 255);
            }
        }
        return result;
    }

    /** Each row from {@code sourceWidth} to {@code width} pixels. */
    private static float[] resizeRows(float[] source, int sourceWidth, int rows, int components, int width) {
        float[] result = new float[width * rows * components];
        Span[] spans = spans(sourceWidth, width);
        for (int y = 0; y < rows; y++) {
            int sourceRow = y * sourceWidth;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                Span span = spans[x];
                int out = (row + x) * components;
                for (int s = span.first; s <= span.last; s++) {
                    float weight = span.weight(s);
                    int in = (sourceRow + s) * components;
                    for (int c = 0; c < components; c++) {
                        result[out + c] += source[in + c] * weight;
                    }
                }
            }
        }
        return result;
    }

    /** Each column from {@code sourceHeight} to {@code height} pixels. */
    private static float[] resizeColumns(float[] source, int width, int sourceHeight, int components, int height) {
        float[] result = new float[width * height * components];
        Span[] spans = spans(sourceHeight, height);
        for (int y = 0; y < height; y++) {
            Span span = spans[y];
            for (int s = span.first; s <= span.last; s++) {
                float weight = span.weight(s);
                int in = s * width * components;
                int out = y * width * components;
                for (int i = 0; i < width * components; i++) {
                    result[out + i] += source[in + i] * weight;
                }
            }
        }
        return result;
    }

    /** For each output pixel along an axis, the source pixels it covers and how much of each. */
    private static Span[] spans(int sourceSize, int size) {
        Span[] spans = new Span[size];
        double scale = (double) sourceSize / size;
        for (int i = 0; i < size; i++) {
            double start = i * scale;
            double end = (i + 1) * scale;
            spans[i] = new Span(start, end);
        }
        return spans;
    }

    /** A stretch {@code [start, end)} of source pixels along one axis. */
    private static final class Span {
        final double start;
        final double end;
        final int first;
        final int last;

        Span(double start, double end) {
            this.start = start;
            this.end = end;
            this.first = (int) Math.floor(start);
            // The last pixel the stretch reaches into; one that only touches its edge doesn't count.
            this.last = Math.max(first, (int) Math.ceil(end) - 1);
        }

        /** The share of the stretch that source pixel {@code s} covers; the shares add up to 1. */
        float weight(int s) {
            double covered = Math.min(end, s + 1) - Math.max(start, s);
            return (float) (covered / (end - start));
        }
    }
}
