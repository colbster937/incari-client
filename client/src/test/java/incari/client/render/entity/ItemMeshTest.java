package incari.client.render.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import incari.client.testing.TestPlatform;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * An item's quads drawn instanced ({@link ModelMesh#ofQuads}, ADR 0027) land where the game's
 * {@code putBakedQuad} puts them: each baked vertex placed by the instance's rows as the rewritten
 * shader places it, against the game's own vertex writes.
 */
class ItemMeshTest {

    /** The game's vertices: position, texture coordinates, normal. */
    private static final class Recorder implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();

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
            vertices.add(new float[] {x, y, z, u, v, nx, ny, nz});
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
    @SuppressWarnings("NullAway") // The quads' materials are only asked for their light emission.
    void placesTheGamesVertices() {
        BakedQuad.MaterialInfo plain = new BakedQuad.MaterialInfo(null, null, null, null, null, -1, null, 0);
        List<BakedQuad> quads = List.of(
                new BakedQuad(
                        new Vector3f(0.1F, 0.2F, 0.3F),
                        new Vector3f(0.9F, 0.2F, 0.3F),
                        new Vector3f(0.9F, 0.8F, 0.3F),
                        new Vector3f(0.1F, 0.8F, 0.3F),
                        UVPair.pack(0.25F, 0.5F),
                        UVPair.pack(0.5F, 0.5F),
                        UVPair.pack(0.5F, 0.75F),
                        UVPair.pack(0.25F, 0.75F),
                        Direction.SOUTH,
                        plain),
                new BakedQuad(
                        new Vector3f(0.0F, 1.0F, 0.0F),
                        new Vector3f(0.0F, 1.0F, 1.0F),
                        new Vector3f(1.0F, 1.0F, 1.0F),
                        new Vector3f(1.0F, 1.0F, 0.0F),
                        UVPair.pack(0.0F, 0.0F),
                        UVPair.pack(0.0F, 0.0625F),
                        UVPair.pack(0.0625F, 0.0625F),
                        UVPair.pack(0.0625F, 0.0F),
                        Direction.UP,
                        plain));
        PoseStack poses = new PoseStack();
        poses.translate(1.5F, -2.25F, 0.125F);
        poses.mulPose(new org.joml.Matrix4f().rotateXYZ(0.3F, 1.1F, -0.4F));
        poses.scale(0.7F, 1.3F, -0.9F);

        Recorder game = new Recorder();
        QuadInstance instance = new QuadInstance();
        for (BakedQuad quad : quads) {
            game.putBakedQuad(poses.last(), quad, instance);
        }

        ModelMesh mesh = Objects.requireNonNull(ModelMesh.ofQuads(quads, "test"));
        assertThat(mesh.quads()).isEqualTo(2);
        assertThat(mesh.rows()).isEqualTo(3);
        float[] rows = new float[12];
        mesh.writeRows(poses.last(), rows, 0);
        // As the rewritten shader reads them: the rows' 3x3 as columns is the transpose, whose
        // inverse is the inverse transpose of the pose.
        Matrix3f normalMatrix = new Matrix3f(
                        rows[0], rows[4], rows[8], rows[1], rows[5], rows[9], rows[2], rows[6], rows[10])
                .transpose()
                .invert();

        ByteBuffer data = Objects.requireNonNull(mesh.vertexData());
        int stride = ModelMesh.VERTEX.getVertexSize();
        for (int v = 0; v < game.vertices.size(); v++) {
            float[] expected = game.vertices.get(v);
            int at = v * stride;
            float lx = data.getFloat(at);
            float ly = data.getFloat(at + 4);
            float lz = data.getFloat(at + 8);
            float x = rows[0] * lx + rows[1] * ly + rows[2] * lz + rows[3];
            float y = rows[4] * lx + rows[5] * ly + rows[6] * lz + rows[7];
            float z = rows[8] * lx + rows[9] * ly + rows[10] * lz + rows[11];
            assertThat(new float[] {x, y, z})
                    .as("vertex %d position", v)
                    .containsExactly(new float[] {expected[0], expected[1], expected[2]}, within(1.0E-5F));
            assertThat(data.getFloat(at + 12)).as("vertex %d u", v).isEqualTo(expected[3]);
            assertThat(data.getFloat(at + 16)).as("vertex %d v", v).isEqualTo(expected[4]);
            Vector3f normal = new Vector3f(
                            data.get(at + 20) / 127.0F, data.get(at + 21) / 127.0F, data.get(at + 22) / 127.0F)
                    .mul(normalMatrix)
                    .normalize();
            assertThat(new float[] {normal.x(), normal.y(), normal.z()})
                    .as("vertex %d normal", v)
                    .containsExactly(new float[] {expected[5], expected[6], expected[7]}, within(1.0E-4F));
            assertThat(data.getInt(at + 24)).as("vertex %d part", v).isZero();
        }
    }
}
