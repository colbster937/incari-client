package incari.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import incari.client.ui.UiAtlas.Region;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari's mark in full: the snow-white pine with a comet circling it ({@code tools/icon}), drawn
 * from the Incari pack's 256-pixel image. Unlike {@link Icons} it has its own colours, so a draw
 * only fades it. Each size is scaled down once, averaging the source's pixels, and copied into
 * the UI atlas at the size it's shown at in screen pixels, so it stays sharp at any GUI scale.
 *
 * <p>Render thread. After the mark's first use at a size, nothing allocates.
 */
public final class Logo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SOURCE = "/incari-pack/assets/incari/textures/gui/icon_256.png";
    private static final int MAX_PIXELS = 256;

    /** The mark by its size in pixels, made as needed. */
    private static final @Nullable Region[] MADE = new Region[MAX_PIXELS + 1];

    /** The source image's ARGB pixels, row by row; empty if it couldn't be read. */
    private static int @Nullable [] source;

    private Logo() {}

    /** Draws the mark {@code size} GUI units square at ({@code x}, {@code y}), at {@code alpha} (0 to 1). */
    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int size, float alpha) {
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int pixels = Math.min(MAX_PIXELS, size * scale);
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        if (pixels <= 0 || a == 0) {
            return;
        }
        Region region = MADE[pixels];
        if (region == null) {
            int[] argb = source();
            if (argb.length == 0) {
                return;
            }
            region = UiAtlas.add(scaled(argb, pixels));
            MADE[pixels] = region;
        }
        Matrix3x2f pose = new Matrix3x2f(graphics.pose()).scale(1.0F / scale);
        int px = x * scale;
        int py = y * scale;
        RoundRects.quad(
                graphics, pose, region, px, py, px + pixels, py + pixels, 0, 0, pixels, pixels, a << 24 | 0xFFFFFF);
    }

    private static int[] source() {
        int[] argb = source;
        if (argb == null) {
            argb = new int[0];
            try (InputStream in = Logo.class.getResourceAsStream(SOURCE)) {
                if (in == null) {
                    LOGGER.warn("Incari's pack has no {}", SOURCE);
                } else {
                    try (NativeImage image = NativeImage.read(in)) {
                        if (image.getWidth() != MAX_PIXELS || image.getHeight() != MAX_PIXELS) {
                            throw new IOException("it's " + image.getWidth() + "x" + image.getHeight());
                        }
                        argb = new int[MAX_PIXELS * MAX_PIXELS];
                        for (int row = 0; row < MAX_PIXELS; row++) {
                            for (int column = 0; column < MAX_PIXELS; column++) {
                                argb[row * MAX_PIXELS + column] = image.getPixel(column, row);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Couldn't read Incari's mark: {}", e.toString());
            }
            source = argb;
        }
        return argb;
    }

    /**
     * The source scaled down to {@code pixels} square: each pixel the average of the source pixels it
     * covers, weighted by their alpha so the transparent edge doesn't darken the white.
     */
    private static NativeImage scaled(int[] argb, int pixels) {
        NativeImage image = new NativeImage(pixels, pixels, false);
        for (int py = 0; py < pixels; py++) {
            int y0 = py * MAX_PIXELS / pixels;
            int y1 = Math.max(y0 + 1, (py + 1) * MAX_PIXELS / pixels);
            for (int px = 0; px < pixels; px++) {
                int x0 = px * MAX_PIXELS / pixels;
                int x1 = Math.max(x0 + 1, (px + 1) * MAX_PIXELS / pixels);
                long alpha = 0;
                long red = 0;
                long green = 0;
                long blue = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int pixel = argb[sy * MAX_PIXELS + sx];
                        int a = pixel >>> 24;
                        alpha += a;
                        red += (long) (pixel >> 16 & 0xFF) * a;
                        green += (long) (pixel >> 8 & 0xFF) * a;
                        blue += (long) (pixel & 0xFF) * a;
                    }
                }
                int count = (y1 - y0) * (x1 - x0);
                int pixel = 0;
                if (alpha > 0) {
                    pixel = (int) ((alpha + count / 2) / count) << 24
                            | (int) (red / alpha) << 16
                            | (int) (green / alpha) << 8
                            | (int) (blue / alpha);
                }
                image.setPixel(px, py, pixel);
            }
        }
        return image;
    }
}
