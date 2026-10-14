package incari.client.render.terrain;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
import incari.client.render.SectionLayers;
import incari.client.render.mesher.Layers;
import incari.mesher.Facings;
import incari.mesher.MeshTables;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * One section mesh's draws, kept between frames (ADR 0030). The game finds them again every frame:
 * per visible section and layer, two hash lookups into its terrain heaps for where the mesh's
 * vertices and indices were allocated, and new objects to describe the draw. On the desktop's
 * fly-through that was two thirds of the render thread. A mesh's allocations only change when its
 * heaps upload it again (a translucent section re-sorted) or free it, and the heaps say so
 * ({@link #moved}); until then its draws are read from here.
 *
 * <p>Each mesh also keeps what Incari's mesher told about it: its solid and cutout quads' runs by
 * facing ({@link Facings}), and the animated sprites it draws. The game's own compiler tells
 * neither, and then the whole layer is drawn and every sprite counts as seen.
 */
public final class SectionDraws {

    /** Per layer, quads in each run by facing; null where the game's compiler meshed the section. */
    final int @Nullable [] facingRuns;

    /** The animated sprites the section draws (bits by id), or null if unknown. */
    final long @Nullable [] animatedSprites;

    /** The sprite ids' generation the mesh was made with ({@code AnimatedSprites}). */
    final int spriteGeneration;

    /** Whether the translucent layer's order holds from every camera ({@code incari.mesher.FixedOrder}). */
    private final boolean fixedOrder;

    /** Set when the heaps move or free one of the mesh's allocations; read on the render thread. */
    private volatile boolean stale = true;

    final boolean[] present = new boolean[MeshTables.LAYER_COUNT];
    final @Nullable GpuBuffer[] vertexBuffers = new GpuBuffer[MeshTables.LAYER_COUNT];
    final int[] baseVertex = new int[MeshTables.LAYER_COUNT];
    final @Nullable GpuBuffer[] indexBuffers = new GpuBuffer[MeshTables.LAYER_COUNT];
    final @Nullable IndexType[] indexTypes = new IndexType[MeshTables.LAYER_COUNT];
    final int[] firstIndex = new int[MeshTables.LAYER_COUNT];
    final int[] indexCount = new int[MeshTables.LAYER_COUNT];

    private SectionDraws(
            int @Nullable [] facingRuns, long @Nullable [] animatedSprites, int spriteGeneration, boolean fixedOrder) {
        this.facingRuns = facingRuns;
        this.animatedSprites = animatedSprites;
        this.spriteGeneration = spriteGeneration;
        this.fixedOrder = fixedOrder;
    }

    /** Hook ({@code CompiledSectionMesh}): what a new mesh's draws start from. */
    public static SectionDraws of(SectionCompiler.Results results) {
        for (MeshData mesh : results.renderedLayers.values()) {
            if (mesh instanceof Facts facts) {
                return new SectionDraws(
                        facts.facingRuns(), facts.animatedSprites(), facts.spriteGeneration(), facts.fixedOrder());
            }
        }
        return new SectionDraws(null, null, 0, false);
    }

    /**
     * Hook ({@code SectionRenderDispatcher.RenderSection.resortTransparency}): whether the section's
     * translucent quads are in an order that holds from every camera, so a re-sort would only write
     * the same order again (the mesher's {@code FixedOrder}).
     */
    public static boolean keepsOrder(SectionMesh mesh) {
        return mesh instanceof CompiledSectionMesh compiled && compiled.incariDraws.fixedOrder;
    }

    /**
     * Hook ({@code UberGpuBuffer}): one of {@code key}'s allocations was made or freed. Keys are the
     * section meshes the game's terrain heaps hold.
     */
    public static void moved(Object key) {
        if (key instanceof CompiledSectionMesh mesh) {
            mesh.incariDraws.stale = true;
        }
    }

    /**
     * Reads the mesh's allocations again if they moved since they were last read, as the game's
     * draw list reads them (under the dispatcher's lock, as the game does).
     */
    void refresh(SectionRenderDispatcher dispatcher, SectionMesh mesh) {
        if (!stale) {
            return;
        }
        stale = false;
        for (ChunkSectionLayer layer : SectionLayers.all()) {
            int i = Layers.index(layer);
            SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
            SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, layer);
            present[i] = slice != null && draw != null && (!draw.hasCustomIndexBuffer() || slice.indexBuffer() != null);
            if (!present[i] || slice == null || draw == null) {
                vertexBuffers[i] = null;
                indexBuffers[i] = null;
                indexTypes[i] = null;
                continue;
            }
            int vertexSize = layer.vertexFormat().getVertexSize();
            vertexBuffers[i] = slice.vertexBuffer();
            baseVertex[i] = (int) (slice.vertexBufferOffset() / vertexSize);
            indexCount[i] = draw.indexCount();
            if (draw.hasCustomIndexBuffer()) {
                IndexType type = draw.indexType();
                indexBuffers[i] = slice.indexBuffer();
                indexTypes[i] = type;
                firstIndex[i] = (int) (slice.indexBufferOffset() / type.bytes);
            } else {
                indexBuffers[i] = null;
                indexTypes[i] = null;
                firstIndex[i] = 0;
            }
        }
    }

    /** Quads in run {@code run} of a layer (see {@link Facings#runOf}). */
    int run(int layer, int run) {
        int[] runs = facingRuns;
        return runs == null ? 0 : runs[layer * Facings.COUNT + run];
    }

    /**
     * What Incari's mesher adds to a layer's mesh data ({@code MeshReply}): the section's runs by
     * facing and its animated sprites, the same for each of its layers.
     */
    public interface Facts {

        int[] facingRuns();

        long[] animatedSprites();

        int spriteGeneration();

        /** Whether the translucent layer is in an order that holds from every camera. */
        boolean fixedOrder();
    }
}
