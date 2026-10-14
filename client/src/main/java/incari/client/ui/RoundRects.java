package incari.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import incari.client.ui.UiAtlas.Region;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Rounded rectangles for the skin: filled, outlined and soft shadows, smooth at any GUI scale.
 *
 * <p>A GUI unit is a whole number of screen pixels, so a corner textured in GUI units shows stair
 * steps. These draw in screen pixels (the pose scaled down by the GUI scale) from a small
 * anti-aliased image per corner size, made on first use: its quarters are the corners, its middle
 * row and column stretch over the edges and middle. No shader of Incari's own (the browser build
 * only has the game's, translated at build time). Cheap enough for Potato.
 *
 * <p>A shape is one render state however many pieces it has, and {@link #box} draws shadow, fill
 * and edge as one: the GUI copies the pose and computes bounds for each state, and moves a state
 * that overlaps the last one up a layer, with draws of its own. The images all live in the
 * {@link UiAtlas}, so shapes of every size batch into one draw.
 *
 * <p>Render thread. Hot path: after a size's first use, a shape allocates its render state, a copy
 * of the pose and its bounds, and nothing else.
 */
public final class RoundRects {

    private static final int FILL = 0;
    private static final int RING = 1;
    private static final int SHADOW = 2;

    /** The largest corner made, in screen pixels; larger ones are drawn at this size. */
    private static final int MAX_PIXELS = 48;

    /** The images by shape and corner size in pixels (shadows by radius and blur both). */
    private static final @Nullable Region[] FILLS = new Region[MAX_PIXELS + 1];

    private static final @Nullable Region[] RINGS = new Region[MAX_PIXELS + 1];
    private static final @Nullable Region[] SHADOWS = new Region[(MAX_PIXELS + 1) * (MAX_PIXELS + 1)];

    private static final int BAND_WIDTH = 64;

    private static @Nullable Region band;

    private static @Nullable Region fade;

    private static final int DOT_SIZE = 8;

    private static @Nullable Region dot;

    private RoundRects() {}

    /** A filled rectangle with corners of {@code radius} GUI units, in {@code argb}. */
    public static void fill(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int argb) {
        box(graphics, x, y, width, height, radius, argb, 0, 0, 0, 0);
    }

    /** A one-pixel outline just inside the rectangle, following the same corners as {@link #fill}. */
    public static void outline(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int argb) {
        box(graphics, x, y, width, height, radius, 0, argb, 0, 0, 0);
    }

    /**
     * A soft shadow or glow around the rectangle, {@code blur} GUI units wide, its middle left
     * out (a translucent panel over it would show it through).
     */
    public static void shadow(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int blur, int argb) {
        box(graphics, x, y, width, height, radius, 0, 0, blur, 0, argb);
    }

    /** {@link #fill} and {@link #outline} with the same corners, as one shape; a color of 0 leaves that part out. */
    public static void box(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int fill, int edge) {
        box(graphics, x, y, width, height, radius, fill, edge, 0, 0, 0);
    }

    /**
     * {@link #shadow}, {@link #fill} and {@link #outline} with the same corners, drawn in that order
     * as one shape: the shadow {@code shadowY} GUI units lower than the rectangle. A color of 0 (or
     * a blur of 0) leaves that part out.
     */
    public static void box(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            int radius,
            int fill,
            int edge,
            int blur,
            int shadowY,
            int shadowArgb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean hasShadow = blur > 0 && (shadowArgb >>> 24) != 0;
        boolean hasFill = (fill >>> 24) != 0;
        boolean hasEdge = (edge >>> 24) != 0;
        if (!hasShadow && !hasFill && !hasEdge) {
            return;
        }
        int scale = scale();
        int px = x * scale;
        int py = y * scale;
        int w = width * scale;
        int h = height * scale;
        Matrix3x2f pose = new Matrix3x2f(graphics.pose()).scale(1.0F / scale);
        @Nullable ScreenRectangle scissor = graphics.incariScissor();
        Box box = null;
        if (hasShadow) {
            int b = Math.min(blur * scale, MAX_PIXELS / 2);
            int r = Math.min(Math.min(radius * scale, MAX_PIXELS / 2), Math.min(width, height) * scale / 2);
            int corner = r + b;
            if (w + 2 * b >= 2 * corner && h + 2 * b >= 2 * corner) {
                box = layer(
                        graphics,
                        box,
                        pose,
                        scissor,
                        SHADOW,
                        texture(SHADOW, r, b),
                        corner,
                        px - b,
                        py + shadowY * scale - b,
                        w + 2 * b,
                        h + 2 * b,
                        shadowArgb);
            }
        }
        int r = Math.min(Math.min(radius * scale, MAX_PIXELS), Math.min(w, h) / 2);
        if (hasFill) {
            box = r <= 0
                    ? layer(graphics, box, pose, scissor, FILL, UiAtlas.white(), -1, px, py, w, h, fill)
                    : layer(graphics, box, pose, scissor, FILL, texture(FILL, r, 0), r, px, py, w, h, fill);
        }
        if (hasEdge) {
            box = r <= 1
                    ? layer(graphics, box, pose, scissor, RING, UiAtlas.white(), -1, px, py, w, h, edge)
                    : layer(graphics, box, pose, scissor, RING, texture(RING, r, 0), r, px, py, w, h, edge);
        }
        if (box != null) {
            box.submit(graphics);
        }
    }

    /**
     * Adds a layer to {@code box}, or to a new one if its image is on another atlas page (after
     * submitting {@code box}); returns the box it went into.
     */
    @SuppressWarnings("ReferenceEquality") // one TextureSetup object per atlas page
    private static Box layer(
            GuiGraphicsExtractor graphics,
            @Nullable Box box,
            Matrix3x2fc pose,
            @Nullable ScreenRectangle scissor,
            int layer,
            Region region,
            int corner,
            int x,
            int y,
            int w,
            int h,
            int argb) {
        Box into = box;
        if (into == null || into.texture != region.texture) {
            if (into != null) {
                into.submit(graphics);
            }
            into = new Box(pose, region.texture, scissor);
        }
        into.set(layer, region, corner, x, y, w, h, argb);
        return into;
    }

    /**
     * A soft vertical band of light, brightest in its middle, across the rectangle: the light
     * sweep over a hovered button. From an image made once, stretched.
     */
    public static void band(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0 || (argb >>> 24) == 0) {
            return;
        }
        Region region = band;
        if (region == null) {
            NativeImage image = new NativeImage(BAND_WIDTH, 1, false);
            for (int px = 0; px < BAND_WIDTH; px++) {
                double s = Math.sin(Math.PI * (px + 0.5) / BAND_WIDTH);
                image.setPixel(px, 0, (int) Math.round(s * s * 255.0) << 24 | 0xFFFFFF);
            }
            region = UiAtlas.add(image);
            band = region;
        }
        stretch(graphics, region, x, y, width, height, argb);
    }

    /** {@code argb} at the rectangle's left edge, fading out smoothly to nothing at its right. */
    public static void fadeRight(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0 || (argb >>> 24) == 0) {
            return;
        }
        Region region = fade;
        if (region == null) {
            NativeImage image = new NativeImage(BAND_WIDTH, 1, false);
            for (int px = 0; px < BAND_WIDTH; px++) {
                float t = 1.0F - (px + 0.5F) / BAND_WIDTH;
                image.setPixel(px, 0, Math.round(t * t * (3.0F - 2.0F * t) * 255.0F) << 24 | 0xFFFFFF);
            }
            region = UiAtlas.add(image);
            fade = region;
        }
        stretch(graphics, region, x, y, width, height, argb);
    }

    /** The whole of {@code region} stretched over the rectangle, in GUI units. */
    private static void stretch(
            GuiGraphicsExtractor graphics, Region region, int x, int y, int width, int height, int argb) {
        quad(
                graphics,
                new Matrix3x2f(graphics.pose()),
                region,
                x,
                y,
                x + width,
                y + height,
                0,
                0,
                region.width,
                region.height,
                argb);
    }

    /**
     * A soft round dot {@code size} screen pixels across, its top left corner at ({@code x},
     * {@code y}) in screen pixels: call between {@link #pixels} and the matching {@code popMatrix}.
     */
    public static void dot(GuiGraphicsExtractor graphics, int x, int y, int size, int argb) {
        Region region = dotImage();
        quad(
                graphics,
                new Matrix3x2f(graphics.pose()),
                region,
                x,
                y,
                x + size,
                y + size,
                0,
                0,
                DOT_SIZE,
                DOT_SIZE,
                argb);
    }

    /**
     * Many soft round dots ({@link #dot}) as one render state: {@code count} of them from {@code
     * dots}, four ints each, its x and y in screen pixels, its size across in screen pixels and its
     * color. The array is copied.
     */
    public static void dots(GuiGraphicsExtractor graphics, int[] dots, int count) {
        if (count <= 0) {
            return;
        }
        int scale = scale();
        Matrix3x2f pose = new Matrix3x2f(graphics.pose()).scale(1.0F / scale);
        int[] copy = java.util.Arrays.copyOf(dots, count * 4);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            minX = Math.min(minX, copy[i * 4]);
            minY = Math.min(minY, copy[i * 4 + 1]);
            maxX = Math.max(maxX, copy[i * 4] + copy[i * 4 + 2]);
            maxY = Math.max(maxY, copy[i * 4 + 1] + copy[i * 4 + 2]);
        }
        Region region = dotImage();
        @Nullable ScreenRectangle scissor = graphics.incariScissor();
        graphics.incariSubmit(
                new Dots(pose, region, copy, count, scissor, bounds(pose, scissor, minX, minY, maxX, maxY)));
    }

    private static Region dotImage() {
        Region region = dot;
        if (region == null) {
            int n = DOT_SIZE;
            NativeImage image = new NativeImage(n, n, false);
            for (int py = 0; py < n; py++) {
                for (int px = 0; px < n; px++) {
                    float dx = (px + 0.5F) / n * 2 - 1;
                    float dy = (py + 0.5F) / n * 2 - 1;
                    float t = Math.max(0, 1 - (float) Math.sqrt(dx * dx + dy * dy));
                    image.setPixel(px, py, Math.round(Math.min(1, t * 1.6F) * 255.0F) << 24 | 0xFFFFFF);
                }
            }
            region = UiAtlas.add(image);
            dot = region;
        }
        return region;
    }

    /** Draws in screen pixels until the matching {@code popMatrix}; returns the GUI scale. */
    public static int pixels(GuiGraphicsExtractor graphics) {
        int scale = scale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0F / scale, 1.0F / scale);
        return scale;
    }

    /** A horizontal line one screen pixel thick along the top of GUI row {@code y}. */
    public static void hairline(GuiGraphicsExtractor graphics, int x, int y, int width, int argb) {
        if (width <= 0 || (argb >>> 24) == 0) {
            return;
        }
        int scale = scale();
        Matrix3x2f pose = new Matrix3x2f(graphics.pose()).scale(1.0F / scale);
        Region white = UiAtlas.white();
        quad(graphics, pose, white, x * scale, y * scale, (x + width) * scale, y * scale + 1, 1, 1, 2, 2, argb);
    }

    /**
     * One quad from the atlas: the part of {@code region} from pixel ({@code u0}, {@code v0}) to
     * ({@code u1}, {@code v1}) of it over the rectangle from ({@code x0}, {@code y0}) to ({@code x1},
     * {@code y1}) in {@code pose}'s units. {@code pose} is the state's own: not to be changed after.
     */
    static void quad(
            GuiGraphicsExtractor graphics,
            Matrix3x2f pose,
            Region region,
            int x0,
            int y0,
            int x1,
            int y1,
            int u0,
            int v0,
            int u1,
            int v1,
            int argb) {
        graphics.incariSubmit(new BlitRenderState(
                RenderPipelines.GUI_TEXTURED,
                region.texture,
                pose,
                x0,
                y0,
                x1,
                y1,
                region.u(u0),
                region.u(u1),
                region.v(v0),
                region.v(v1),
                argb,
                graphics.incariScissor()));
    }

    private static int scale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** The rectangle in {@code pose}'s units as the GUI sees it, clipped; null if clipped away. */
    private static @Nullable ScreenRectangle bounds(
            Matrix3x2fc pose, @Nullable ScreenRectangle scissor, int x0, int y0, int x1, int y1) {
        ScreenRectangle bounds = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
        return scissor != null ? scissor.intersection(bounds) : bounds;
    }

    private static Region texture(int shape, int radius, int blur) {
        @Nullable Region[] made = shape == FILL ? FILLS : shape == RING ? RINGS : SHADOWS;
        int index = shape == SHADOW ? radius * (MAX_PIXELS + 1) + blur : radius;
        Region region = made[index];
        if (region == null) {
            region = make(shape, radius, blur);
            made[index] = region;
        }
        return region;
    }

    /** Draws the shape's image, white with its coverage in alpha, into the atlas. */
    private static Region make(int shape, int radius, int blur) {
        int c = radius + blur;
        int size = 2 * c + 1;
        NativeImage image = new NativeImage(size, size, false);
        // The shape is a rounded square filling the image (less the blur), so its straight edge
        // is the middle pixel; four samples a side per pixel for the edge's coverage.
        float half = size / 2.0F - blur;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                float alpha;
                if (shape == SHADOW) {
                    // Outside the shape only, fading out over the blur: under a translucent
                    // panel any shadow would show through.
                    float d = distance(px + 0.5F, py + 0.5F, size, half, radius);
                    float outside = Math.max(0.0F, Math.min(1.0F, d + 0.5F));
                    float fade = 1.0F - Math.max(0.0F, Math.min(1.0F, d / blur));
                    alpha = outside * fade * fade;
                } else {
                    float inside = 0;
                    float insideInset = 0;
                    for (int sy = 0; sy < 4; sy++) {
                        for (int sx = 0; sx < 4; sx++) {
                            float d = distance(px + (sx + 0.5F) / 4.0F, py + (sy + 0.5F) / 4.0F, size, half, radius);
                            if (d < 0) {
                                inside++;
                            }
                            if (d < -1) {
                                insideInset++;
                            }
                        }
                    }
                    alpha = (shape == FILL ? inside : inside - insideInset) / 16.0F;
                }
                int a = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
                image.setPixel(px, py, a << 24 | 0xFFFFFF);
            }
        }
        return UiAtlas.add(image);
    }

    /** The signed distance from the point to a rounded square centred in the image. */
    private static float distance(float x, float y, int size, float half, int radius) {
        float qx = Math.abs(x - size / 2.0F) - (half - radius);
        float qy = Math.abs(y - size / 2.0F) - (half - radius);
        float ox = Math.max(qx, 0);
        float oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /** Adds a textured quad, wound as the game's blits are. */
    private static void vertices(
            VertexConsumer consumer,
            Matrix3x2fc pose,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1,
            int argb) {
        consumer.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0).setColor(argb);
        consumer.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1).setColor(argb);
        consumer.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1).setColor(argb);
        consumer.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0).setColor(argb);
    }

    /**
     * One shape: up to three layers (shadow, fill, edge), each a nine-slice of its image ({@code
     * corner} pixels a side) or, with no corner, plain from the atlas's white. In screen pixels.
     */
    private static final class Box implements GuiElementRenderState {

        private final Matrix3x2fc pose;
        private final TextureSetup texture;
        private final @Nullable ScreenRectangle scissor;
        private @Nullable ScreenRectangle bounds;

        // Each layer's image, corner (-1 for plain), rectangle and color: fields, not arrays, so a
        // shape is one object.
        private @Nullable Region shadow;
        private int shadowCorner;
        private int shadowX;
        private int shadowY;
        private int shadowW;
        private int shadowH;
        private int shadowColor;
        private @Nullable Region fill;
        private int fillCorner;
        private int fillColor;
        private @Nullable Region ring;
        private int ringCorner;
        private int ringColor;
        /** The fill's and the edge's rectangle. */
        private int x;

        private int y;
        private int w;
        private int h;

        Box(Matrix3x2fc pose, TextureSetup texture, @Nullable ScreenRectangle scissor) {
            this.pose = pose;
            this.texture = texture;
            this.scissor = scissor;
        }

        void set(int layer, Region region, int corner, int x, int y, int w, int h, int argb) {
            if (layer == SHADOW) {
                shadow = region;
                shadowCorner = corner;
                shadowX = x;
                shadowY = y;
                shadowW = w;
                shadowH = h;
                shadowColor = argb;
                return;
            }
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            if (layer == FILL) {
                fill = region;
                fillCorner = corner;
                fillColor = argb;
            } else {
                ring = region;
                ringCorner = corner;
                ringColor = argb;
            }
        }

        /** Works out the bounds from the layers set, and hands the shape to the GUI. */
        void submit(GuiGraphicsExtractor graphics) {
            int minX;
            int minY;
            int maxX;
            int maxY;
            if (fill != null || ring != null) {
                minX = x;
                minY = y;
                maxX = x + w;
                maxY = y + h;
                if (shadow != null) {
                    minX = Math.min(minX, shadowX);
                    minY = Math.min(minY, shadowY);
                    maxX = Math.max(maxX, shadowX + shadowW);
                    maxY = Math.max(maxY, shadowY + shadowH);
                }
            } else {
                minX = shadowX;
                minY = shadowY;
                maxX = shadowX + shadowW;
                maxY = shadowY + shadowH;
            }
            bounds = RoundRects.bounds(pose, scissor, minX, minY, maxX, maxY);
            graphics.incariSubmit(this);
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            // Shadow, fill, edge: the order they're seen in.
            if (shadow != null) {
                slices(consumer, shadow, shadowCorner, shadowX, shadowY, shadowW, shadowH, shadowColor, false);
            }
            if (fill != null) {
                slices(consumer, fill, fillCorner, x, y, w, h, fillColor, true);
            }
            if (ring != null) {
                slices(consumer, ring, ringCorner, x, y, w, h, ringColor, false);
            }
        }

        private void slices(
                VertexConsumer consumer, Region region, int c, int x, int y, int w, int h, int argb, boolean middle) {
            if (c < 0) {
                // Plain: from the middle of the white square.
                float u0 = region.u(1);
                float u1 = region.u(2);
                float v0 = region.v(1);
                float v1 = region.v(2);
                if (middle) {
                    vertices(consumer, pose, x, y, x + w, y + h, u0, v0, u1, v1, argb);
                } else {
                    // A one-pixel outline.
                    vertices(consumer, pose, x, y, x + w, y + 1, u0, v0, u1, v1, argb);
                    vertices(consumer, pose, x, y + h - 1, x + w, y + h, u0, v0, u1, v1, argb);
                    vertices(consumer, pose, x, y + 1, x + 1, y + h - 1, u0, v0, u1, v1, argb);
                    vertices(consumer, pose, x + w - 1, y + 1, x + w, y + h - 1, u0, v0, u1, v1, argb);
                }
                return;
            }
            int inner = w - 2 * c;
            int innerH = h - 2 * c;
            int right = x + w - c;
            int bottom = y + h - c;
            // The image's columns and rows: the left corner, the middle one, the right corner.
            float ua = region.u(0);
            float ub = region.u(c);
            float uc = region.u(c + 1);
            float ud = region.u(2 * c + 1);
            float va = region.v(0);
            float vb = region.v(c);
            float vc = region.v(c + 1);
            float vd = region.v(2 * c + 1);
            // Corners.
            vertices(consumer, pose, x, y, x + c, y + c, ua, va, ub, vb, argb);
            vertices(consumer, pose, right, y, right + c, y + c, uc, va, ud, vb, argb);
            vertices(consumer, pose, x, bottom, x + c, bottom + c, ua, vc, ub, vd, argb);
            vertices(consumer, pose, right, bottom, right + c, bottom + c, uc, vc, ud, vd, argb);
            // Edges: the middle column and row, stretched.
            if (inner > 0) {
                vertices(consumer, pose, x + c, y, right, y + c, ub, va, uc, vb, argb);
                vertices(consumer, pose, x + c, bottom, right, bottom + c, ub, vc, uc, vd, argb);
            }
            if (innerH > 0) {
                vertices(consumer, pose, x, y + c, x + c, bottom, ua, vb, ub, vc, argb);
                vertices(consumer, pose, right, y + c, right + c, bottom, uc, vb, ud, vc, argb);
            }
            if (middle && inner > 0 && innerH > 0) {
                vertices(consumer, pose, x + c, y + c, right, bottom, ub, vb, uc, vc, argb);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }

        @Override
        public TextureSetup textureSetup() {
            return texture;
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return scissor;
        }

        @Override
        public @Nullable ScreenRectangle bounds() {
            return bounds;
        }
    }

    /** Many dots ({@link #dots}): the snow on the title screen. */
    private static final class Dots implements GuiElementRenderState {

        private final Matrix3x2fc pose;
        private final Region region;
        /** x, y, size, color for each dot: the state's own copy. */
        private final int[] dots;

        private final int count;
        private final @Nullable ScreenRectangle scissor;
        private final @Nullable ScreenRectangle bounds;

        Dots(
                Matrix3x2fc pose,
                Region region,
                int[] dots,
                int count,
                @Nullable ScreenRectangle scissor,
                @Nullable ScreenRectangle bounds) {
            this.pose = pose;
            this.region = region;
            this.dots = dots;
            this.count = count;
            this.scissor = scissor;
            this.bounds = bounds;
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return scissor;
        }

        @Override
        public @Nullable ScreenRectangle bounds() {
            return bounds;
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            float u0 = region.u(0);
            float u1 = region.u(DOT_SIZE);
            float v0 = region.v(0);
            float v1 = region.v(DOT_SIZE);
            for (int i = 0; i < count; i++) {
                int x = dots[i * 4];
                int y = dots[i * 4 + 1];
                int size = dots[i * 4 + 2];
                vertices(consumer, pose, x, y, x + size, y + size, u0, v0, u1, v1, dots[i * 4 + 3]);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }

        @Override
        public TextureSetup textureSetup() {
            return region.texture;
        }
    }
}
