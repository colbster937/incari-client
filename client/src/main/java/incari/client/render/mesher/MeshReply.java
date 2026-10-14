package incari.client.render.mesher;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import incari.client.render.terrain.SectionDraws;
import incari.mesher.Facings;
import incari.mesher.MeshTables;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Turns an encoded mesh ({@code incari.mesher.MeshResult}) into the game's
 * {@code SectionCompiler.Results}: each layer's vertices copied into the section's buffer pack as
 * the game's own compiler leaves them, the translucent layer with its sorted index buffer and the
 * quad centers the game re-sorts it from later, and the visibility set. From there the game uploads
 * and draws the section as it would its own mesh. Each layer's mesh data also carries what the game's
 * compiler doesn't tell: the section's quad runs by facing and its animated sprites (ADR 0030).
 */
final class MeshReply {

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

    private MeshReply() {}

    /**
     * Reads a mesh from {@code reply} (little-endian, from its position) into results.
     *
     * @param builders where the vertices and indices go, as the game's compiler would put them
     * @param spriteGeneration the animated sprite ids' generation the tables were made with
     */
    static SectionCompiler.Results results(
            ByteBuffer reply,
            SectionBufferBuilderPack builders,
            List<BlockEntity> blockEntities,
            int spriteGeneration) {
        ByteBuffer in = reply.slice().order(ByteOrder.LITTLE_ENDIAN);
        SectionCompiler.Results results = new SectionCompiler.Results();
        results.blockEntities.addAll(blockEntities);
        long visibility = in.getLong();
        int vertexSize = in.get() & 0xFF;
        @Nullable MeshData[] meshes = new MeshData[LAYERS.length];
        ByteBufferBuilder.@Nullable Result[] vertexResults = new ByteBufferBuilder.Result[LAYERS.length];
        int[] vertexCounts = new int[LAYERS.length];
        for (int layer = 0; layer < LAYERS.length; layer++) {
            int vertices = in.getInt();
            int bytes = in.getInt();
            vertexCounts[layer] = vertices;
            if (vertices > 0) {
                ChunkSectionLayer sectionLayer = LAYERS[layer];
                if (bytes != vertices * sectionLayer.vertexFormat().getVertexSize() || vertexSize == 0) {
                    throw new IllegalStateException("A " + vertexSize + "-byte mesh for the "
                            + sectionLayer.vertexFormat().getVertexSize() + "-byte format of " + sectionLayer.label());
                }
                vertexResults[layer] = copy(in, bytes, builders.buffer(sectionLayer));
            } else {
                in.position(in.position() + bytes);
            }
        }
        int centroidFloats = in.getInt();
        CompactVectorArray centroids = new CompactVectorArray(centroidFloats / 3);
        for (int i = 0; i < centroidFloats / 3; i++) {
            centroids.set(i, in.getFloat(), in.getFloat(), in.getFloat());
        }
        boolean intIndices = in.get() != 0;
        int indexBytes = in.getInt();
        int indexStart = in.position();
        in.position(indexStart + indexBytes);
        boolean fixedOrder = in.get() != 0;
        int[] facingRuns = new int[in.getInt()];
        for (int i = 0; i < facingRuns.length; i++) {
            facingRuns[i] = in.getInt();
        }
        if (facingRuns.length != MeshTables.LAYER_COUNT * Facings.COUNT) {
            throw new IllegalStateException("Facing runs for " + facingRuns.length / Facings.COUNT + " layers");
        }
        long[] animatedSprites = new long[in.getInt()];
        for (int i = 0; i < animatedSprites.length; i++) {
            animatedSprites[i] = in.getLong();
        }
        Facts facts = new Facts(facingRuns, animatedSprites, spriteGeneration, fixedOrder);
        in.position(indexStart);
        for (int layer = 0; layer < LAYERS.length; layer++) {
            ByteBufferBuilder.Result vertices = vertexResults[layer];
            if (vertices == null) {
                continue;
            }
            ChunkSectionLayer sectionLayer = LAYERS[layer];
            int count = vertexCounts[layer];
            IndexType indexType = IndexType.least(count);
            MeshData.DrawState drawState = new MeshData.DrawState(
                    sectionLayer.vertexFormat(),
                    count,
                    PrimitiveTopology.QUADS.indexCount(count),
                    PrimitiveTopology.QUADS,
                    indexType);
            if (sectionLayer == ChunkSectionLayer.TRANSLUCENT) {
                if ((indexType == IndexType.INT) != intIndices) {
                    throw new IllegalStateException("Sorted indices of the wrong size for " + count + " vertices");
                }
                ByteBufferBuilder.Result indices = copy(in, indexBytes, builders.buffer(sectionLayer));
                meshes[layer] = new SortedMeshData(vertices, drawState, indices, facts);
                results.transparencyState = new MeshData.SortState(centroids, indexType);
            } else {
                meshes[layer] = new FactsMeshData(vertices, drawState, facts);
            }
            results.renderedLayers.put(sectionLayer, meshes[layer]);
        }
        results.visibilitySet = visibility(visibility);
        return results;
    }

    /** Copies the next {@code bytes} of {@code in} into {@code target} as one result. */
    private static ByteBufferBuilder.Result copy(ByteBuffer in, int bytes, ByteBufferBuilder target) {
        // Reserved, then built: the result views the reserved bytes, which are filled through it.
        target.reserve(bytes);
        ByteBufferBuilder.Result result = target.build();
        if (result == null) {
            throw new IllegalStateException("Empty buffer for " + bytes + " bytes");
        }
        result.byteBuffer().put(0, in, in.position(), bytes);
        in.position(in.position() + bytes);
        return result;
    }

    static VisibilitySet visibility(long bits) {
        VisibilitySet set = new VisibilitySet();
        for (int a = 0; a < 6; a++) {
            for (int b = 0; b < 6; b++) {
                if ((bits & 1L << (a + 6 * b)) != 0) {
                    set.set(DIRECTIONS[a], DIRECTIONS[b], true);
                }
            }
        }
        return set;
    }

    /** What Incari's mesher tells about a section beyond its vertices. */
    private static final class Facts {
        final int[] facingRuns;
        final long[] animatedSprites;
        final int spriteGeneration;
        final boolean fixedOrder;

        Facts(int[] facingRuns, long[] animatedSprites, int spriteGeneration, boolean fixedOrder) {
            this.facingRuns = facingRuns;
            this.animatedSprites = animatedSprites;
            this.spriteGeneration = spriteGeneration;
            this.fixedOrder = fixedOrder;
        }
    }

    /** A layer's mesh, with the section's {@link Facts} for its kept draws. */
    private static class FactsMeshData extends MeshData implements SectionDraws.Facts {

        private final Facts facts;

        FactsMeshData(ByteBufferBuilder.Result vertices, MeshData.DrawState drawState, Facts facts) {
            super(vertices, drawState);
            this.facts = facts;
        }

        @Override
        public int[] facingRuns() {
            return facts.facingRuns;
        }

        @Override
        public long[] animatedSprites() {
            return facts.animatedSprites;
        }

        @Override
        public int spriteGeneration() {
            return facts.spriteGeneration;
        }

        @Override
        public boolean fixedOrder() {
            return facts.fixedOrder;
        }
    }

    /** A translucent mesh whose index buffer was sorted where it was meshed. */
    private static final class SortedMeshData extends FactsMeshData {

        private final ByteBufferBuilder.Result indices;

        SortedMeshData(
                ByteBufferBuilder.Result vertices,
                MeshData.DrawState drawState,
                ByteBufferBuilder.Result indices,
                Facts facts) {
            super(vertices, drawState, facts);
            this.indices = indices;
        }

        @Override
        public ByteBuffer indexBuffer() {
            return indices.byteBuffer();
        }

        @Override
        public void close() {
            super.close();
            indices.close();
        }
    }
}
