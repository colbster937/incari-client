package incari.client.render.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import incari.client.testing.TestPlatform;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * A baked model placed by its part rows lands where the game's own {@code ModelPart.render} puts
 * its vertices, with the same normals and texture coordinates, for a tree with rotations, a
 * non-uniform scale, a hidden part and a part that skips its own cubes.
 */
class ModelMeshTest {

    /** What the game writes per vertex. */
    private record Written(float x, float y, float z, float u, float v, float nx, float ny, float nz) {}

    /** Collects the game's vertices. */
    private static final class Capture implements VertexConsumer {
        final List<Written> vertices = new ArrayList<>();
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            // The game sets the normal last: the vertex is complete.
            vertices.add(new Written(x, y, z, u, v, nx, ny, nz));
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private static ModelPart.Cube cube(float minX, float minY, float minZ, float size, boolean mirror) {
        return new ModelPart.Cube(
                0,
                0,
                minX,
                minY,
                minZ,
                size,
                size,
                size,
                0.25F,
                0.25F,
                0.25F,
                mirror,
                64,
                64,
                EnumSet.allOf(Direction.class));
    }

    private static ModelPart part(List<ModelPart.Cube> cubes, Map<String, ModelPart> children) {
        return new ModelPart(cubes, children);
    }

    /** root → body (turned) → arm (scaled unevenly), head (hidden) → hat, leg (skips its cubes) → foot. */
    private static ModelPart model() {
        ModelPart hat = part(List.of(cube(-4, -10, -4, 8, false)), Map.of());
        ModelPart head = part(List.of(cube(-4, -8, -4, 8, false)), Map.of("hat", hat));
        head.visible = false;
        ModelPart arm = part(List.of(cube(-1, -2, -1, 4, true)), Map.of());
        arm.setPos(5, 2, 0);
        arm.xRot = 0.7F;
        arm.xScale = 1.5F;
        arm.yScale = 0.75F;
        ModelPart foot = part(List.of(cube(-2, 10, -2, 4, false)), Map.of());
        foot.zRot = -0.3F;
        ModelPart leg = part(List.of(cube(-2, 0, -2, 4, false)), Map.of("foot", foot));
        leg.setPos(2, 12, 0);
        leg.skipDraw = true;
        Map<String, ModelPart> bodyChildren = new LinkedHashMap<>();
        bodyChildren.put("arm", arm);
        bodyChildren.put("head", head);
        bodyChildren.put("leg", leg);
        ModelPart body = part(List.of(cube(-4, 0, -2, 8, false)), bodyChildren);
        body.setPos(0, 1, 0);
        body.setRotation(0.1F, 0.8F, -0.2F);
        return part(List.of(), Map.of("body", body));
    }

    @Test
    void theRowsPlaceTheBakedVerticesWhereTheGameDraws() {
        TestPlatform.install();
        ModelPart root = model();
        PoseStack placed = new PoseStack();
        placed.translate(3.0F, -1.5F, 7.0F);
        placed.rotate(new org.joml.Quaternionf().rotationY(2.2F));
        placed.scale(-0.9375F, -0.9375F, 0.9375F);

        Capture game = new Capture();
        root.render(placed, game, 0, 0);

        ModelMesh mesh = ModelMesh.of(root, "test");
        assertThat(mesh).isNotNull();
        float[] rows = new float[mesh.rows() * 4];
        mesh.writeRows(placed.last(), rows, 0);
        ByteBuffer data = mesh.vertexData();
        assertThat(data).isNotNull();
        int stride = ModelMesh.VERTEX.getVertexSize();

        List<Written> ours = new ArrayList<>();
        for (int vertex = 0; vertex < mesh.quads() * 4; vertex++) {
            int at = vertex * stride;
            int part = data.getInt(at + 24);
            int row = part * 12;
            float lx = data.getFloat(at);
            float ly = data.getFloat(at + 4);
            float lz = data.getFloat(at + 8);
            float[] p = new float[3];
            for (int r = 0; r < 3; r++) {
                p[r] = rows[row + r * 4] * lx
                        + rows[row + r * 4 + 1] * ly
                        + rows[row + r * 4 + 2] * lz
                        + rows[row + r * 4 + 3];
            }
            if (p[0] == 0 && p[1] == 0 && p[2] == 0 && rows[row] == 0 && rows[row + 5] == 0) {
                // A part the game doesn't draw: collapsed.
                continue;
            }
            // The normal as the shader turns it: the inverse transpose of the part's 3×3, normalized.
            org.joml.Matrix3f m = new org.joml.Matrix3f(
                    rows[row],
                    rows[row + 4],
                    rows[row + 8],
                    rows[row + 1],
                    rows[row + 5],
                    rows[row + 9],
                    rows[row + 2],
                    rows[row + 6],
                    rows[row + 10]);
            Vector3f n =
                    new Vector3f(data.get(at + 20) / 127.0F, data.get(at + 21) / 127.0F, data.get(at + 22) / 127.0F);
            m.invert().transpose().transform(n).normalize();
            ours.add(new Written(p[0], p[1], p[2], data.getFloat(at + 12), data.getFloat(at + 16), n.x, n.y, n.z));
        }

        // Hidden: the head and the hat below it; skipped: the leg's own cube, not the foot's.
        assertThat(game.vertices).hasSize(3 * 6 * 4); // The body, the arm and the foot.
        assertThat(ours).hasSameSizeAs(game.vertices);
        for (int i = 0; i < ours.size(); i++) {
            Written expected = game.vertices.get(i);
            Written actual = ours.get(i);
            assertThat(actual.x()).isCloseTo(expected.x(), within(1e-5F));
            assertThat(actual.y()).isCloseTo(expected.y(), within(1e-5F));
            assertThat(actual.z()).isCloseTo(expected.z(), within(1e-5F));
            assertThat(actual.u()).isEqualTo(expected.u());
            assertThat(actual.v()).isEqualTo(expected.v());
            assertThat(actual.nx()).isCloseTo(expected.nx(), within(1e-4F));
            assertThat(actual.ny()).isCloseTo(expected.ny(), within(1e-4F));
            assertThat(actual.nz()).isCloseTo(expected.nz(), within(1e-4F));
        }
    }
}
