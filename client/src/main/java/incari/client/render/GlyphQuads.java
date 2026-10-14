package incari.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Objects;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * A glyph's four vertices written in one call (ADR 0031), for text in the world: sign lines, name
 * tags, text displays. The game writes each vertex as four calls ({@code addVertex}, then
 * {@code setColor}, {@code setUv} and {@code setLight}), and each of those checks which of the
 * format's elements are still to fill. World text comes in two formats
 * ({@link DefaultVertexFormat#POSITION_TEX_LIGHTMAP_COLOR}, and {@link DefaultVertexFormat#POSITION_TEX_COLOR}
 * for a name tag's see-through pass, which has no light), so the elements go straight where they
 * belong, and the pose's terms are read once a quad instead of once a vertex. A crowded lobby
 * draws thousands of glyphs a frame; their vertices were an eighth of the page's work.
 *
 * <p>The bytes are the game's: each position is transformed as {@link VertexMath#addVertex}
 * transforms it (the same fused multiply-adds in the same order, JOML's), and {@code BufferBuilder}
 * writes the vertex's elements with its own helpers ({@code incariVertex}), a light only where the
 * format has one, as its {@code setLight} does. Other formats and other consumers take the game's
 * path.
 *
 * <p>Render thread only.
 */
public final class GlyphQuads {

    private static final VertexFormat LIT = DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
    private static final VertexFormat SEE_THROUGH = DefaultVertexFormat.POSITION_TEX_COLOR;

    private static volatile boolean enabled = true;

    private GlyphQuads() {}

    /** Whether glyphs are written this way (the page's {@code ?glyphs=game} turns it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Whether {@code builder} holds one of world text's formats. */
    @SuppressWarnings(
            "ReferenceEquality") // The game's formats are its shared constants; equals compares every element.
    private static boolean textFormat(BufferBuilder builder) {
        VertexFormat format = builder.incariFormat();
        return format == LIT || format == SEE_THROUGH;
    }

    /**
     * Hook ({@code BakedSheetGlyph.render}): the quad the game writes as four vertices at
     * {@code (xa, ya)} to {@code (xd, yd)}, all at {@code z}, with the texture's corners
     * {@code (u0, v0)}, {@code (u0, v1)}, {@code (u1, v1)}, {@code (u1, v0)} in that order. False
     * if it wasn't written (the caller writes it the game's way).
     */
    public static boolean put(
            VertexConsumer consumer,
            Matrix4fc pose,
            float xa,
            float ya,
            float xb,
            float yb,
            float xc,
            float yc,
            float xd,
            float yd,
            float z,
            int color,
            float u0,
            float v0,
            float u1,
            float v1,
            int light) {
        if (!enabled || !(consumer instanceof BufferBuilder builder) || !textFormat(builder)) {
            return false;
        }
        BUILDER.builder = builder;
        quad(BUILDER, pose, xa, ya, xb, yb, xc, yc, xd, yd, z, color, u0, v0, u1, v1, light);
        return true;
    }

    /** Where a quad's vertices go: a {@code BufferBuilder} in the game, a recorder in tests. */
    interface Sink {
        /** One transformed vertex, with the color (ARGB) and light the game gives it. */
        void vertex(float x, float y, float z, int color, float u, float v, int light);
    }

    /** Each vertex into the builder at hand, whole. */
    private static final class BuilderSink implements Sink {
        @Nullable
        BufferBuilder builder;

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int light) {
            Objects.requireNonNull(builder).incariVertex(x, y, z, color, u, v, light);
        }
    }

    private static final BuilderSink BUILDER = new BuilderSink();

    /** The quad's four vertices, transformed, into {@code sink}. */
    static void quad(
            Sink sink,
            Matrix4fc pose,
            float xa,
            float ya,
            float xb,
            float yb,
            float xc,
            float yc,
            float xd,
            float yd,
            float z,
            int color,
            float u0,
            float v0,
            float u1,
            float v1,
            int light) {
        float m00 = pose.m00();
        float m01 = pose.m01();
        float m02 = pose.m02();
        float m10 = pose.m10();
        float m11 = pose.m11();
        float m12 = pose.m12();
        // The z term and the constant term are the same for all four corners: added first, as the
        // game's innermost fused multiply-add adds them.
        float tx = Math.fma(pose.m20(), z, pose.m30());
        float ty = Math.fma(pose.m21(), z, pose.m31());
        float tz = Math.fma(pose.m22(), z, pose.m32());
        sink.vertex(
                Math.fma(m00, xa, Math.fma(m10, ya, tx)),
                Math.fma(m01, xa, Math.fma(m11, ya, ty)),
                Math.fma(m02, xa, Math.fma(m12, ya, tz)),
                color,
                u0,
                v0,
                light);
        sink.vertex(
                Math.fma(m00, xb, Math.fma(m10, yb, tx)),
                Math.fma(m01, xb, Math.fma(m11, yb, ty)),
                Math.fma(m02, xb, Math.fma(m12, yb, tz)),
                color,
                u0,
                v1,
                light);
        sink.vertex(
                Math.fma(m00, xc, Math.fma(m10, yc, tx)),
                Math.fma(m01, xc, Math.fma(m11, yc, ty)),
                Math.fma(m02, xc, Math.fma(m12, yc, tz)),
                color,
                u1,
                v1,
                light);
        sink.vertex(
                Math.fma(m00, xd, Math.fma(m10, yd, tx)),
                Math.fma(m01, xd, Math.fma(m11, yd, ty)),
                Math.fma(m02, xd, Math.fma(m12, yd, tz)),
                color,
                u1,
                v0,
                light);
    }
}
