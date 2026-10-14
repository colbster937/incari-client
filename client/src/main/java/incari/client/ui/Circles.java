package incari.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Filled circles and rings of any size, smooth at their edges: a mesh of a few dozen quads around
 * the middle, each edge feathered out over one screen pixel by the vertices' alpha. No texture, so
 * a large one (a minimap's frame) is as sharp as a small one, and no shader of Incari's own. Drawn
 * from the UI atlas's white texel ({@link UiAtlas#white}), so circles batch with the icons, shapes
 * and panels around them instead of each switch between them costing a draw call.
 *
 * <p>Render thread. One render state a call, as each of the game's blits is.
 */
public final class Circles {

    private Circles() {}

    /** Fills a circle of {@code radius} GUI units around {@code centerX, centerY} in {@code argb}. */
    public static void disc(GuiGraphicsExtractor graphics, float centerX, float centerY, float radius, int argb) {
        add(graphics, centerX, centerY, radius, 0, argb);
    }

    /** A ring {@code width} GUI units thick, its outside edge at {@code radius}. */
    public static void ring(
            GuiGraphicsExtractor graphics, float centerX, float centerY, float radius, float width, int argb) {
        if (width > 0) {
            add(graphics, centerX, centerY, radius, width, argb);
        }
    }

    /**
     * A ring's part from straight up, {@code fraction} of the way round clockwise (a cooldown
     * filling): its ends cut square.
     */
    public static void arc(
            GuiGraphicsExtractor graphics,
            float centerX,
            float centerY,
            float radius,
            float width,
            float fraction,
            int argb) {
        if (width > 0 && fraction > 0) {
            add(graphics, centerX, centerY, radius, width, Math.min(1, fraction), argb);
        }
    }

    private static void add(GuiGraphicsExtractor graphics, float cx, float cy, float radius, float width, int argb) {
        add(graphics, cx, cy, radius, width, 1, argb);
    }

    private static void add(
            GuiGraphicsExtractor graphics, float cx, float cy, float radius, float width, float fraction, int argb) {
        if (radius <= 0 || (argb >>> 24) == 0) {
            return;
        }
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        float poseScale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        float pixel = 1.0F / Math.max(1.0E-3F, scale * poseScale);
        int edge = (int) Math.ceil(radius + pixel);
        @Nullable ScreenRectangle scissor = graphics.incariScissor();
        ScreenRectangle box = new ScreenRectangle(
                        (int) Math.floor(cx) - edge, (int) Math.floor(cy) - edge, 2 * edge + 2, 2 * edge + 2)
                .transformMaxBounds(pose);
        @Nullable ScreenRectangle bounds = scissor != null ? scissor.intersection(box) : box;
        UiAtlas.Region white = UiAtlas.white();
        graphics.incariSubmit(new Mesh(
                pose,
                cx,
                cy,
                radius,
                width,
                fraction,
                pixel,
                argb,
                white.texture,
                white.u(1.5F),
                white.v(1.5F),
                scissor,
                bounds));
    }

    /** Each side count's corners round a circle from angle 0: cos and sin of each, in turn. Made as needed. */
    private static final float[] @Nullable [] CORNERS = new float[129][];

    /**
     * {@code sides}' corners ({@link #sides}' range): cos and sin of {@code i} sides' angle at
     * {@code 2i} and {@code 2i + 1}. Shared, not to be written. Render thread.
     */
    public static float[] corners(int sides) {
        float[] corners = CORNERS[sides];
        if (corners == null) {
            corners = new float[2 * (sides + 1)];
            for (int i = 0; i <= sides; i++) {
                double angle = 2 * Math.PI * i / sides;
                corners[2 * i] = (float) Math.cos(angle);
                corners[2 * i + 1] = (float) Math.sin(angle);
            }
            CORNERS[sides] = corners;
        }
        return corners;
    }

    /**
     * How many sides a circle of this many screen pixels' radius gets: enough that each side's
     * middle is within a tenth of a pixel of the true circle (radius x (1 - cos(pi / sides)), about
     * pi^2 x radius / (2 x sides^2)), under the pixel the edge is feathered over. A minimap's frame
     * at scale 2 gets 69; with a side a pixel and a bit, it had 115, all built every frame.
     */
    public static int sides(float radiusPixels) {
        return Math.max(24, Math.min(128, Math.round((float) (Math.PI * Math.sqrt(5 * Math.max(0, radiusPixels))))));
    }

    /** One circle or ring. */
    private record Mesh(
            Matrix3x2fc pose,
            float cx,
            float cy,
            float radius,
            float width,
            float fraction,
            float pixel,
            int argb,
            TextureSetup textureSetup,
            float u,
            float v,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds)
            implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer consumer) {
            int sides = sides(radius / pixel);
            int clear = argb & 0x00FFFFFF;
            float half = pixel / 2;
            // From the outside in: feathered edge, solid band (the ring's, or the disc's rim), and
            // for a ring its feathered inner edge.
            float outer = radius + half;
            float solidOuter = Math.max(0, radius - half);
            float solidInner = width > 0 ? Math.max(0, radius - width + half) : 0;
            float inner = width > 0 ? Math.max(0, radius - width - half) : 0;
            // The corners come from a table made once: a frame's circles cost no sines. An arc starts
            // a quarter turn back (straight up), so its corners are the table's turned: cos(a - 90°)
            // is sin(a), sin(a - 90°) is -cos(a).
            float[] corners = corners(sides);
            boolean arc = fraction < 1;
            int count = (int) Math.ceil(sides * fraction);
            for (int i = 0; i < count; i++) {
                float c0 = arc ? corners[2 * i + 1] : corners[2 * i];
                float s0 = arc ? -corners[2 * i] : corners[2 * i + 1];
                float c1;
                float s1;
                if (arc && i == count - 1) {
                    // The arc's last side ends where the arc does, short of the next corner.
                    double end = -Math.PI / 2 + 2 * Math.PI * fraction;
                    c1 = (float) Math.cos(end);
                    s1 = (float) Math.sin(end);
                } else {
                    c1 = arc ? corners[2 * i + 3] : corners[2 * i + 2];
                    s1 = arc ? -corners[2 * i + 2] : corners[2 * i + 3];
                }
                band(consumer, c0, s0, c1, s1, solidOuter, argb, outer, clear);
                if (width > 0) {
                    band(consumer, c0, s0, c1, s1, solidInner, argb, solidOuter, argb);
                    band(consumer, c0, s0, c1, s1, inner, clear, solidInner, argb);
                } else {
                    // The middle: a triangle (a quad with two corners at the centre).
                    vertex(consumer, 0, 0, 0, argb);
                    vertex(consumer, c1, s1, solidOuter, argb);
                    vertex(consumer, c0, s0, solidOuter, argb);
                    vertex(consumer, 0, 0, 0, argb);
                }
            }
        }

        /**
         * The quad between two radii across one side, wound as the game's GUI quads are (its
         * pipeline culls the other way round).
         */
        private void band(
                VertexConsumer consumer,
                float c0,
                float s0,
                float c1,
                float s1,
                float in,
                int inColor,
                float out,
                int outColor) {
            vertex(consumer, c0, s0, out, outColor);
            vertex(consumer, c0, s0, in, inColor);
            vertex(consumer, c1, s1, in, inColor);
            vertex(consumer, c1, s1, out, outColor);
        }

        private void vertex(VertexConsumer consumer, float cos, float sin, float r, int color) {
            consumer.addVertexWith2DPose(pose, cx + cos * r, cy + sin * r)
                    .setUv(u, v)
                    .setColor(color);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }
    }
}
