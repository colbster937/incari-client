package incari.client.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import incari.client.testing.TestPlatform;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link VertexMath#putBakedQuad} writes what the game's {@code putBakedQuad} writes, to the bit. */
class VertexMathTest {

    /** Every vertex written, as the 11 values the game hands over. */
    private static final class Recorder implements VertexConsumer {
        final List<String> vertices = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setColor(int color) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
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

        @Override
        public void addVertex(
                float x,
                float y,
                float z,
                int color,
                float u,
                float v,
                int overlay,
                int light,
                float nx,
                float ny,
                float nz) {
            vertices.add(
                    Float.floatToRawIntBits(x) + " " + Float.floatToRawIntBits(y) + " " + Float.floatToRawIntBits(z)
                            + " " + color + " " + u + " " + v + " " + overlay + " " + light + " "
                            + Float.floatToRawIntBits(nx) + " " + Float.floatToRawIntBits(ny) + " "
                            + Float.floatToRawIntBits(nz));
        }
    }

    @BeforeAll
    static void onTheRenderThread() {
        TestPlatform.install();
        // Once per test JVM: another test class may have made this thread the render thread already.
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    @SuppressWarnings("NullAway") // The quad's material is only asked for its light emission.
    void writesTheGamesVertices() {
        BakedQuad quad = new BakedQuad(
                new Vector3f(0.1F, 0.2F, 0.3F),
                new Vector3f(0.9F, 0.2F, 0.3F),
                new Vector3f(0.9F, 0.8F, 0.3F),
                new Vector3f(0.1F, 0.8F, 0.3F),
                UVPair.pack(0.25F, 0.5F),
                UVPair.pack(0.5F, 0.5F),
                UVPair.pack(0.5F, 0.75F),
                UVPair.pack(0.25F, 0.75F),
                Direction.SOUTH,
                new BakedQuad.MaterialInfo(null, null, null, null, null, -1, null, 3));
        PoseStack poses = new PoseStack();
        poses.translate(1.5F, -2.25F, 0.125F);
        poses.mulPose(new org.joml.Matrix4f().rotateXYZ(0.3F, 1.1F, -0.4F));
        poses.scale(0.7F, 1.3F, -0.9F);
        QuadInstance instance = new QuadInstance();
        instance.setColor(0, 0xFF102030);
        instance.setLightCoords(2, 0x00F000A0);
        instance.setOverlayCoords(0x000A0003);

        Recorder game = new Recorder();
        // The game's own path: VertexConsumer's default, which Recorder doesn't override.
        game.putBakedQuad(poses.last(), quad, instance);
        Recorder incari = new Recorder();
        assertThat(VertexMath.putBakedQuad(incari, poses.last(), quad, instance))
                .isTrue();

        assertThat(incari.vertices).hasSize(4).isEqualTo(game.vertices);
    }
}
