package incari.tools.webbuild;

import java.awt.image.BufferedImage;

/**
 * How alike two renders of the same scene look: the structural similarity index (SSIM, Wang et
 * al. 2004) over each color channel, averaged. SSIM compares local brightness, contrast and
 * structure in an 11-pixel Gaussian window rather than pixel values, so a texel's worth of
 * rasterization or filtering difference between two GPUs costs little, while missing or wrong
 * geometry, a wrong color, a missing effect or a shifted image costs a lot. 1 means identical.
 *
 * <p>Also reports the share of pixels whose largest channel differs by more than {@link #CHANGED}
 * (a plain measure to read beside SSIM) and draws a heat map of where the images differ.
 */
final class ImageDiff {

    /** A channel difference, out of 255, above which a pixel counts as changed. */
    static final int CHANGED = 32;

    private static final int RADIUS = 5;
    private static final double SIGMA = 1.5;
    private static final double C1 = Math.pow(0.01 * 255, 2);
    private static final double C2 = Math.pow(0.03 * 255, 2);
    private static final float[] KERNEL = kernel();

    /**
     * @param ssim the mean SSIM of the red, green and blue channels
     * @param changed the share of pixels with a channel more than {@link #CHANGED} apart
     * @param heatmap the expected image in gray, with dissimilar regions in red
     */
    record Result(double ssim, double changed, BufferedImage heatmap) {}

    private ImageDiff() {}

    static Result compare(BufferedImage expected, BufferedImage actual) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        if (actual.getWidth() != width || actual.getHeight() != height) {
            throw new IllegalArgumentException("The images differ in size: " + width + "x" + height + " and "
                    + actual.getWidth() + "x" + actual.getHeight());
        }
        int[] a = expected.getRGB(0, 0, width, height, null, 0, width);
        int[] b = actual.getRGB(0, 0, width, height, null, 0, width);
        float[] dissimilarity = new float[width * height];
        double total = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            total += channel(a, b, shift, width, height, dissimilarity);
        }
        int changed = 0;
        for (int i = 0; i < a.length; i++) {
            int largest = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                largest = Math.max(largest, Math.abs(((a[i] >> shift) & 0xFF) - ((b[i] >> shift) & 0xFF)));
            }
            if (largest > CHANGED) {
                changed++;
            }
        }
        return new Result(total / 3, changed / (double) a.length, heatmap(a, dissimilarity, width, height));
    }

    /** The mean SSIM of one channel; adds each pixel's dissimilarity (1 - SSIM, over three) to {@code into}. */
    private static double channel(int[] a, int[] b, int shift, int width, int height, float[] into) {
        int size = width * height;
        float[] x = new float[size];
        float[] y = new float[size];
        float[] xx = new float[size];
        float[] yy = new float[size];
        float[] xy = new float[size];
        for (int i = 0; i < size; i++) {
            float p = (a[i] >> shift) & 0xFF;
            float q = (b[i] >> shift) & 0xFF;
            x[i] = p;
            y[i] = q;
            xx[i] = p * p;
            yy[i] = q * q;
            xy[i] = p * q;
        }
        float[] scratch = new float[size];
        for (float[] plane : new float[][] {x, y, xx, yy, xy}) {
            blur(plane, scratch, width, height);
        }
        double sum = 0;
        for (int i = 0; i < size; i++) {
            double mx = x[i];
            double my = y[i];
            double vx = xx[i] - mx * mx;
            double vy = yy[i] - my * my;
            double cov = xy[i] - mx * my;
            double ssim = ((2 * mx * my + C1) * (2 * cov + C2)) / ((mx * mx + my * my + C1) * (vx + vy + C2));
            sum += ssim;
            into[i] += (float) ((1 - ssim) / 3);
        }
        return sum / size;
    }

    /** A separable Gaussian blur in place; edges repeat their outermost pixel. */
    private static void blur(float[] plane, float[] scratch, int width, int height) {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float value = 0;
                for (int k = -RADIUS; k <= RADIUS; k++) {
                    int sx = Math.clamp(x + k, 0, width - 1);
                    value += KERNEL[k + RADIUS] * plane[row + sx];
                }
                scratch[row + x] = value;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float value = 0;
                for (int k = -RADIUS; k <= RADIUS; k++) {
                    int sy = Math.clamp(y + k, 0, height - 1);
                    value += KERNEL[k + RADIUS] * scratch[sy * width + x];
                }
                plane[y * width + x] = value;
            }
        }
    }

    private static float[] kernel() {
        float[] kernel = new float[2 * RADIUS + 1];
        float sum = 0;
        for (int k = -RADIUS; k <= RADIUS; k++) {
            kernel[k + RADIUS] = (float) Math.exp(-(k * k) / (2 * SIGMA * SIGMA));
            sum += kernel[k + RADIUS];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }
        return kernel;
    }

    private static BufferedImage heatmap(int[] expected, float[] dissimilarity, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < expected.length; i++) {
            int rgb = expected[i];
            int gray = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 6;
            float heat = Math.clamp(dissimilarity[i] * 2, 0, 1);
            int red = Math.round(gray + (255 - gray) * heat);
            int other = Math.round(gray * (1 - heat));
            image.setRGB(i % width, i / width, (red << 16) | (other << 8) | other);
        }
        return image;
    }
}
