package incari.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import incari.client.ui.UiAtlas.Region;
import incari.framework.hud.Shape;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * The canvas's small shapes ({@link Shape}): waypoint icons, the minimap's arrow. Each is painted,
 * as {@link Icons} are, anti-aliased into the UI atlas at the size it's shown at in screen pixels the first
 * time it's needed: white inside a thin dark rim, so tinting colors the inside and the rim stays
 * dark against any terrain or sky. Described as signed distances, so one shape's every size is the
 * same shape.
 *
 * <p>Render thread. After a shape's first use at a size, nothing allocates.
 */
public final class Shapes {

    private static final int MAX_PIXELS = 128;

    /** The rim's color: a cold near-black, a little see-through. */
    private static final int RIM = 0xE0101820;

    private static final Map<Shape, @Nullable Region[]> MADE = new EnumMap<>(Shape.class);

    private Shapes() {}

    /**
     * Draws {@code shape} centred on {@code centerX, centerY}, {@code size} GUI units across, turned
     * {@code rotation} degrees clockwise, its inside in {@code argb}.
     */
    public static void draw(
            GuiGraphicsExtractor graphics,
            Shape shape,
            float centerX,
            float centerY,
            float size,
            float rotation,
            int argb) {
        Matrix3x2fStack pose = graphics.pose();
        // The pose may be scaled (a HUD element at 1.5x): the image is made for the pixels it covers.
        float poseScale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int pixels = Math.min(MAX_PIXELS, Math.round(size * scale * poseScale));
        if (pixels < 2 || (argb >>> 24) == 0) {
            return;
        }
        @Nullable Region[] sizes = MADE.get(shape);
        if (sizes == null) {
            sizes = new Region[MAX_PIXELS + 1];
            MADE.put(shape, sizes);
        }
        Region region = sizes[pixels];
        if (region == null) {
            region = make(shape, pixels);
            sizes[pixels] = region;
        }
        Matrix3x2f own = new Matrix3x2f(pose).translate(centerX, centerY);
        if (rotation != 0) {
            own.rotate((float) Math.toRadians(rotation));
        }
        own.scale(size / pixels);
        int half = pixels / 2;
        RoundRects.quad(graphics, own, region, -half, -half, pixels - half, pixels - half, 0, 0, pixels, pixels, argb);
    }

    private static Region make(Shape shape, int pixels) {
        NativeImage image = new NativeImage(pixels, pixels, false);
        // The rim's width in the shape's units (it spans -1 to 1): about a pixel and a half, more on
        // large icons, so it reads at every size.
        float unit = 2.0F / pixels;
        float rim = Math.max(1.25F, pixels * 0.07F) * unit;
        int samples = 4;
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                float fill = 0;
                float edge = 0;
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        float x = (px + (sx + 0.5F) / samples) * unit - 1;
                        float y = (py + (sy + 0.5F) / samples) * unit - 1;
                        float d = distance(shape, x, y);
                        if (d < -rim) {
                            fill++;
                        } else if (d < 0) {
                            edge++;
                        }
                    }
                }
                float total = samples * samples;
                float coverage = (fill + edge * (RIM >>> 24) / 255.0F) / total;
                int alpha = Math.round(Math.min(1, coverage) * 255);
                // White where it's filled, the rim's dark where it's rim, by their shares of the pixel.
                float white = fill + edge == 0 ? 0 : fill / (fill + edge * (RIM >>> 24) / 255.0F);
                int r = Math.round(255 * white + (RIM >> 16 & 0xFF) * (1 - white));
                int g = Math.round(255 * white + (RIM >> 8 & 0xFF) * (1 - white));
                int b = Math.round(255 * white + (RIM & 0xFF) * (1 - white));
                image.setPixel(px, py, alpha << 24 | r << 16 | g << 8 | b);
            }
        }
        return UiAtlas.add(image);
    }

    /** The signed distance from {@code x, y} (each -1 to 1, y down) to the shape's edge: negative inside. */
    static float distance(Shape shape, float x, float y) {
        return switch (shape) {
            case CIRCLE -> (float) Math.sqrt(x * x + y * y) - 0.92F;
            case SQUARE -> roundBox(x, y, 0.78F, 0.78F, 0.2F);
            case DIAMOND -> polygon(DIAMOND, x, y);
            case TRIANGLE -> polygon(TRIANGLE, x, y);
            case STAR -> polygon(STAR, x, y);
            case HEART -> heart(x, y);
            case HEXAGON -> polygon(HEXAGON, x, y);
            case CROSS -> cross(x, y);
            case ARROW -> polygon(ARROW, x, y);
        };
    }

    private static final float[] DIAMOND = {0, -0.97F, 0.8F, 0, 0, 0.97F, -0.8F, 0};

    /** Pointing up, its middle where its weight is, so it sits centred. */
    private static final float[] TRIANGLE = {0, -0.9F, 0.95F, 0.75F, -0.95F, 0.75F};

    private static final float[] HEXAGON = regular(6, 0.95F, 0.95F);

    private static final float[] STAR = star();

    /** A pointer: the tip up, a notch at the back. */
    private static final float[] ARROW = {0, -0.95F, 0.72F, 0.8F, 0, 0.42F, -0.72F, 0.8F};

    private static float[] regular(int sides, float rx, float ry) {
        float[] points = new float[sides * 2];
        for (int i = 0; i < sides; i++) {
            double angle = -Math.PI / 2 + i * 2 * Math.PI / sides;
            points[2 * i] = (float) (Math.cos(angle) * rx);
            points[2 * i + 1] = (float) (Math.sin(angle) * ry);
        }
        return points;
    }

    private static float[] star() {
        float[] points = new float[20];
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float radius = i % 2 == 0 ? 1.0F : 0.44F;
            points[2 * i] = (float) (Math.cos(angle) * radius);
            // A little lower, so the star's weight sits in the middle.
            points[2 * i + 1] = (float) (Math.sin(angle) * radius) + 0.06F;
        }
        return points;
    }

    private static float roundBox(float x, float y, float halfWidth, float halfHeight, float radius) {
        float qx = Math.abs(x) - halfWidth + radius;
        float qy = Math.abs(y) - halfHeight + radius;
        float ox = Math.max(qx, 0);
        float oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /** An X: two bars crossing, rounded at their ends. */
    private static float cross(float x, float y) {
        float c = (float) Math.sqrt(0.5);
        float u = (x + y) * c;
        float v = (x - y) * c;
        return Math.min(roundBox(u, v, 1.0F, 0.27F, 0.2F), roundBox(v, u, 1.0F, 0.27F, 0.2F));
    }

    /** Inigo Quilez's heart, its point down, scaled to fit. */
    private static float heart(float x, float y) {
        float scale = 1.6F;
        float hx = Math.abs(x) / scale;
        float hy = (0.9F - y) / scale;
        float d;
        if (hy + hx > 1.0F) {
            float dx = hx - 0.25F;
            float dy = hy - 0.75F;
            d = (float) Math.sqrt(dx * dx + dy * dy) - (float) Math.sqrt(2.0) / 4.0F;
        } else {
            float ax = hx;
            float ay = hy - 1.0F;
            float m = 0.5F * Math.max(hx + hy, 0);
            float bx = hx - m;
            float by = hy - m;
            d = (float) Math.sqrt(Math.min(ax * ax + ay * ay, bx * bx + by * by)) * Math.signum(hx - hy);
        }
        return d * scale;
    }

    /** Inigo Quilez's distance to a polygon given as x, y pairs. */
    private static float polygon(float[] v, float x, float y) {
        int n = v.length / 2;
        float d = (x - v[0]) * (x - v[0]) + (y - v[1]) * (y - v[1]);
        float sign = 1;
        for (int i = 0, j = n - 1; i < n; j = i, i++) {
            float ex = v[2 * j] - v[2 * i];
            float ey = v[2 * j + 1] - v[2 * i + 1];
            float wx = x - v[2 * i];
            float wy = y - v[2 * i + 1];
            float t = Math.max(0, Math.min(1, (wx * ex + wy * ey) / (ex * ex + ey * ey)));
            float bx = wx - ex * t;
            float by = wy - ey * t;
            d = Math.min(d, bx * bx + by * by);
            boolean c1 = y >= v[2 * i + 1];
            boolean c2 = y < v[2 * j + 1];
            boolean c3 = ex * wy > ey * wx;
            if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) {
                sign = -sign;
            }
        }
        return sign * (float) Math.sqrt(d);
    }
}
