package incari.client.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.blaze3d.vertex.VertexConsumer;
import incari.client.testing.TestPlatform;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link GlyphQuads} writes a glyph's quad as the game's four vertex calls write it: the same
 * positions to the bit (transformed as {@code BufferBuilder} transforms them, {@link VertexMath}),
 * and the same color, texture corners and light in the same order ({@code BufferBuilder} packs them
 * with its own helpers either way).
 */
class GlyphQuadsTest {

    @BeforeAll
    static void platform() {
        TestPlatform.install();
    }

    /** The game's vertices, as {@code BufferBuilder} receives them after its transform. */
    private static final class Recorder implements VertexConsumer {
        final List<String> vertices = new ArrayList<>();
        private String vertex = "";

        @Override
        public VertexConsumer addVertex(Matrix4fc pose, float x, float y, float z) {
            // BufferBuilder's own override: VertexMath's transform.
            return VertexMath.addVertex(this, pose, x, y, z);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertex = Float.floatToRawIntBits(x) + " " + Float.floatToRawIntBits(y) + " " + Float.floatToRawIntBits(z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            vertex += " color " + color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            vertex += " uv " + Float.floatToRawIntBits(u) + " " + Float.floatToRawIntBits(v);
            return this;
        }

        @Override
        public VertexConsumer setLight(int light) {
            vertices.add(vertex + " light " + light);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void writesTheGamesVertices() {
        // A name tag's pose: turned to the camera, scaled to text size, some way off.
        Matrix4f pose = new Matrix4f()
                .translate(3.25F, 1.875F, -7.5F)
                .rotateXYZ(0.21F, -2.3F, 0.05F)
                .scale(0.025F, -0.025F, 0.025F);
        float x0 = -21.5F;
        float x1 = -15.5F;
        float y0 = 0.0F;
        float y1 = 8.0F;
        float shear0 = 0.1F;
        float shear1 = 0.6F;
        float thick = 0.1F;
        float z = 0.03F;
        int color = 0xC8F0E0D0;
        int light = 0x00F000A0;
        float u0 = 0.125F;
        float u1 = 0.1328125F;
        float v0 = 0.25F;
        float v1 = 0.2578125F;

        // BakedSheetGlyph.render's four vertices, as the game writes them.
        Recorder game = new Recorder();
        game.addVertex(pose, x0 + shear0 - thick, y0 - thick, z)
                .setColor(color)
                .setUv(u0, v0)
                .setLight(light);
        game.addVertex(pose, x0 + shear1 - thick, y1 + thick, z)
                .setColor(color)
                .setUv(u0, v1)
                .setLight(light);
        game.addVertex(pose, x1 + shear1 + thick, y1 + thick, z)
                .setColor(color)
                .setUv(u1, v1)
                .setLight(light);
        game.addVertex(pose, x1 + shear0 + thick, y0 - thick, z)
                .setColor(color)
                .setUv(u1, v0)
                .setLight(light);

        List<String> quad = new ArrayList<>();
        GlyphQuads.quad(
                (x, y, vz, vertexColor, u, v, vertexLight) -> quad.add(Float.floatToRawIntBits(x) + " "
                        + Float.floatToRawIntBits(y) + " " + Float.floatToRawIntBits(vz)
                        + " color " + vertexColor
                        + " uv " + Float.floatToRawIntBits(u) + " " + Float.floatToRawIntBits(v)
                        + " light " + vertexLight),
                pose,
                x0 + shear0 - thick,
                y0 - thick,
                x0 + shear1 - thick,
                y1 + thick,
                x1 + shear1 + thick,
                y1 + thick,
                x1 + shear0 + thick,
                y0 - thick,
                z,
                color,
                u0,
                v0,
                u1,
                v1,
                light);

        assertThat(quad).containsExactlyElementsOf(game.vertices);
    }
}
