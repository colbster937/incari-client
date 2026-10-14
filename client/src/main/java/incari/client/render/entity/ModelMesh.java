package incari.client.render.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * One entity model's cubes, baked once into a vertex buffer in each part's own space (ADR
 * 0024), and the part tree that places them: per instance, {@link #writeRows} walks the tree as
 * the game's {@code ModelPart.render} does (same visibility rules, same {@code translateAndRotate})
 * and writes each drawn part's matrix as three rows. A part the game wouldn't draw gets zero rows,
 * which collapses its cubes to a point.
 *
 * <p>The vertex order is the game's: parts in the order it renders them, each part's cubes and
 * faces in its order, so an instanced model draws the same triangles as the game's vertices.
 *
 * <p>An item's quads bake the same way ({@link #ofQuads}, ADR 0027): one part, placed by the item's
 * own pose, its vertices the baked quads' in the order the game writes them.
 *
 * <p>Render thread only.
 */
final class ModelMesh implements AutoCloseable {

    /** The per-vertex format: position and normal in the part's space, texture coordinates, part. */
    static final VertexFormat VERTEX = VertexFormat.builder(0)
            .addAttribute("IncariLocalPosition", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("IncariLocalNormal", GpuFormat.RGBA8_SNORM)
            .addAttribute("IncariPart", GpuFormat.R32_UINT)
            .build();

    /** Three rows of four floats a part. */
    static final int ROW_BYTES = 16;

    static final int PART_BYTES = 3 * ROW_BYTES;

    private final ModelPart[] parts;
    private final int[][] children;
    /** Each node's index among the drawn parts (those with cubes), or -1. */
    private final int[] drawn;

    private final int drawnCount;
    private final int quads;
    /** The vertices until they're uploaded, then null. */
    private @Nullable ByteBuffer vertexData;

    private @Nullable GpuBuffer vertices;
    private final String label;
    /** Reused per instance: the game's own pose stack, so the transforms are its arithmetic. */
    private final PoseStack poseStack = new PoseStack();

    long lastUsedFrame;

    private ModelMesh(
            ModelPart[] parts,
            int[][] children,
            int[] drawn,
            int drawnCount,
            int quads,
            ByteBuffer vertexData,
            String label) {
        this.parts = parts;
        this.children = children;
        this.drawn = drawn;
        this.drawnCount = drawnCount;
        this.quads = quads;
        this.vertexData = vertexData;
        this.label = label;
    }

    /**
     * Bakes an item's quads as one part, each quad's four vertices as the game writes them
     * ({@code putBakedQuad}): position and texture coordinates as baked, the face's direction as
     * the normal. Null if there are none.
     */
    static @Nullable ModelMesh ofQuads(List<BakedQuad> quads, String label) {
        if (quads.isEmpty()) {
            return null;
        }
        ByteBuffer data = ByteBuffer.allocateDirect(quads.size() * 4 * VERTEX.getVertexSize())
                .order(ByteOrder.nativeOrder());
        for (BakedQuad quad : quads) {
            Vector3fc normal = quad.direction().getUnitVec3f();
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3fc position = quad.position(vertex);
                long uv = quad.packedUV(vertex);
                data.putFloat(position.x()).putFloat(position.y()).putFloat(position.z());
                data.putFloat(UVPair.unpackU(uv)).putFloat(UVPair.unpackV(uv));
                data.put(snorm(normal.x()))
                        .put(snorm(normal.y()))
                        .put(snorm(normal.z()))
                        .put((byte) 0);
                data.putInt(0);
            }
        }
        data.flip();
        return new ModelMesh(new ModelPart[0], new int[0][], new int[0], 1, quads.size(), data, label);
    }

    /** Bakes {@code model}; null if it has nothing to draw. The vertex buffer comes at {@link #upload}. */
    static @Nullable ModelMesh bake(Model<?> model) {
        return of(model.root(), model.getClass().getSimpleName());
    }

    /** Bakes the model under {@code root}; null if it has nothing to draw. */
    static @Nullable ModelMesh of(ModelPart root, String label) {
        List<ModelPart> parts = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        List<List<ModelPart.Cube>> cubes = new ArrayList<>();
        Map<String, Integer> byPath = new HashMap<>();
        // visit() reaches every cube in render order, with its part's path from the root ("" for the
        // root, "/body/head" below it): the part tree is rebuilt from the paths, parents first.
        root.visit(new PoseStack(), (pose, path, cubeIndex, cube) -> {
            int node = node(root, path, parts, parents, cubes, byPath);
            cubes.get(node).add(cube);
        });
        int count = parts.size();
        int[] drawn = new int[count];
        int drawnCount = 0;
        int quads = 0;
        for (int i = 0; i < count; i++) {
            drawn[i] = cubes.get(i).isEmpty() ? -1 : drawnCount++;
            for (ModelPart.Cube cube : cubes.get(i)) {
                quads += cube.polygons.length;
            }
        }
        if (quads == 0) {
            return null;
        }
        List<List<Integer>> childLists = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            childLists.add(new ArrayList<>());
        }
        for (int i = 1; i < count; i++) {
            childLists.get(parents.get(i)).add(i);
        }
        int[][] children = new int[count][];
        for (int i = 0; i < count; i++) {
            children[i] = childLists.get(i).stream().mapToInt(Integer::intValue).toArray();
        }

        ByteBuffer data =
                ByteBuffer.allocateDirect(quads * 4 * VERTEX.getVertexSize()).order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) {
            for (ModelPart.Cube cube : cubes.get(i)) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    Vector3fc normal = polygon.normal();
                    for (ModelPart.Vertex vertex : polygon.vertices()) {
                        data.putFloat(vertex.worldX()).putFloat(vertex.worldY()).putFloat(vertex.worldZ());
                        data.putFloat(vertex.u()).putFloat(vertex.v());
                        data.put(snorm(normal.x()))
                                .put(snorm(normal.y()))
                                .put(snorm(normal.z()))
                                .put((byte) 0);
                        data.putInt(drawn[i]);
                    }
                }
            }
        }
        data.flip();
        return new ModelMesh(parts.toArray(ModelPart[]::new), children, drawn, drawnCount, quads, data, label);
    }

    /** The node for {@code path}, adding it and the parts above it if new. */
    private static int node(
            ModelPart root,
            String path,
            List<ModelPart> parts,
            List<Integer> parents,
            List<List<ModelPart.Cube>> cubes,
            Map<String, Integer> byPath) {
        Integer known = byPath.get(path);
        if (known != null) {
            return known;
        }
        int parent;
        ModelPart part;
        if (path.isEmpty()) {
            parent = -1;
            part = root;
        } else {
            int slash = path.lastIndexOf('/');
            String parentPath = path.substring(0, slash);
            parent = node(root, parentPath, parts, parents, cubes, byPath);
            part = parts.get(parent).getChild(path.substring(slash + 1));
        }
        int index = parts.size();
        parts.add(part);
        parents.add(parent);
        cubes.add(new ArrayList<>());
        byPath.put(path, index);
        return index;
    }

    /** The game's normal byte: clamped, scaled by 127, truncated. */
    private static byte snorm(float value) {
        return (byte) (int) (Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F);
    }

    int quads() {
        return quads;
    }

    /** Creates the vertex buffer if it isn't yet. Outside a render pass. */
    void upload() {
        ByteBuffer data = vertexData;
        if (vertices == null && data != null) {
            vertices = RenderSystem.getDevice()
                    .createBuffer(() -> "Incari instanced model " + label, GpuBuffer.USAGE_VERTEX, data);
            vertexData = null;
        }
    }

    /** The vertex buffer, once {@link #upload uploaded}. */
    @Nullable
    GpuBuffer vertices() {
        return vertices;
    }

    /** The baked vertices, before upload; for tests. */
    @Nullable
    ByteBuffer vertexData() {
        return vertexData;
    }

    /** Rows an instance takes. */
    int rows() {
        return drawnCount * 3;
    }

    /**
     * Writes an instance's part matrices into {@code rows} from {@code offset} (in floats): the
     * model posed by {@code setupAnim} already, placed by {@code pose}. Hot path: allocates only
     * what the game's {@code translateAndRotate} does.
     */
    void writeRows(PoseStack.Pose pose, float[] rows, int offset) {
        if (parts.length == 0) {
            // An item's quads: one part, at the item's own pose.
            writeMatrix(pose.pose(), rows, offset);
            return;
        }
        poseStack.last().set(pose);
        walk(0, true, rows, offset);
    }

    private static void writeMatrix(Matrix4f m, float[] rows, int at) {
        rows[at] = m.m00();
        rows[at + 1] = m.m10();
        rows[at + 2] = m.m20();
        rows[at + 3] = m.m30();
        rows[at + 4] = m.m01();
        rows[at + 5] = m.m11();
        rows[at + 6] = m.m21();
        rows[at + 7] = m.m31();
        rows[at + 8] = m.m02();
        rows[at + 9] = m.m12();
        rows[at + 10] = m.m22();
        rows[at + 11] = m.m32();
    }

    private void walk(int node, boolean parentShown, float[] rows, int offset) {
        ModelPart part = parts[node];
        boolean shown = parentShown && part.visible;
        if (shown) {
            poseStack.pushPose();
            part.translateAndRotate(poseStack);
        }
        int index = drawn[node];
        if (index >= 0) {
            int at = offset + index * 12;
            if (shown && !part.skipDraw) {
                writeMatrix(poseStack.last().pose(), rows, at);
            } else {
                Arrays.fill(rows, at, at + 12, 0.0F);
            }
        }
        for (int child : children[node]) {
            walk(child, shown, rows, offset);
        }
        if (shown) {
            poseStack.popPose();
        }
    }

    @Override
    public void close() {
        if (vertices != null) {
            vertices.close();
        }
    }
}
