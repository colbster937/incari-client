package incari.client.render.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import incari.client.render.AnimatedSprites;
import incari.client.render.SectionLayers;
import incari.client.render.mesher.Layers;
import incari.mesher.Facings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * The frame's terrain draw list, as the game's {@code LevelRenderer.prepareChunkRendersIndirect}
 * makes it (ADR 0030): the visible sections' draws grouped by the heap they're in, one indirect
 * command each, and each section's position and fade, in the same order with the same values. What
 * differs is the cost. The game looked every section's allocations up in its heaps' hash maps and
 * made objects for every draw, command and section every frame (two thirds of the desktop's render
 * thread in the fly-through); here a section's draws are kept until its mesh moves
 * ({@link SectionDraws}), and the commands and section data are written straight into buffers kept
 * from frame to frame.
 *
 * <p>Where Incari's mesher grouped a section's solid and cutout quads by facing, the runs facing
 * away from the camera are left out of its commands ({@link Facings#visible}): the GPU would have
 * culled each of those triangles after shading its vertices. Translucent quads keep their sorted
 * order and are drawn whole.
 *
 * <p>Render thread only, once a frame. Hot path: nothing is allocated per section.
 */
public final class TerrainDrawLists {

    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
    private static final int COMMAND_BYTES = 20;
    private static final int SECTION_BYTES = 16;

    private static volatile boolean enabled = true;
    private static volatile boolean cullFacings = true;
    private static final TerrainDrawLists INSTANCE = new TerrainDrawLists();

    private final List<List<Group>> groups = new ArrayList<>();
    private final int[] groupCounts = new int[LAYERS.length];
    private long[] seenSprites = new long[4];
    private final int[] ranges = new int[2 * Facings.COUNT];

    private @Nullable GpuBuffer commandBuffer;
    private ByteBuffer commandBytes = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    private @Nullable GpuBuffer sectionBuffer;
    private ByteBuffer sectionBytes = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    private final List<GpuBuffer> retired = new ArrayList<>();
    private int sections;

    private TerrainDrawLists() {
        for (int i = 0; i < LAYERS.length; i++) {
            groups.add(new ArrayList<>());
        }
    }

    /** The page's {@code ?drawlists=game}: the game's own draw list, to compare. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** The page's {@code ?faces=all}: every quad drawn, whichever way it faces. */
    public static void setFacingCulling(boolean on) {
        cullFacings = on;
    }

    /** Hook ({@code LevelRenderer.prepareChunkRendersIndirect}): whether this makes the list. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Hook: the frame's terrain draws, with indirect commands.
     *
     * @param visible the sections the occlusion graph reached, nearest first
     * @param fadeSeconds how long a new section takes to fade in (the game's option)
     * @param camera where the frame is drawn from
     */
    public static ChunkSectionsToRender prepare(
            @Nullable SectionRenderDispatcher dispatcher,
            List<SectionRenderDispatcher.RenderSection> visible,
            Matrix4fc modelView,
            boolean respectTranslucentOrder,
            double fadeSeconds,
            Vec3 camera) {
        return INSTANCE.build(dispatcher, visible, modelView, respectTranslucentOrder, fadeSeconds, camera);
    }

    private ChunkSectionsToRender build(
            @Nullable SectionRenderDispatcher dispatcher,
            List<SectionRenderDispatcher.RenderSection> visible,
            Matrix4fc modelView,
            boolean respectTranslucentOrder,
            double fadeSeconds,
            Vec3 camera) {
        for (GpuBuffer buffer : retired) {
            buffer.close();
        }
        retired.clear();
        java.util.Arrays.fill(groupCounts, 0);
        java.util.Arrays.fill(seenSprites, 0L);
        sections = 0;
        sectionBytes.clear();
        int largestIndexCount = 0;
        boolean spritesKnown = true;
        if (dispatcher != null) {
            dispatcher.lock();
            try {
                long fadeDuration = Util.toMillis(fadeSeconds);
                long now = Util.getMillis();
                int spriteGeneration = AnimatedSprites.generation();
                boolean culling = cullFacings;
                Group lastTranslucent = null;
                for (int s = 0; s < visible.size(); s++) {
                    SectionRenderDispatcher.RenderSection section = visible.get(s);
                    SectionMesh mesh = section.getSectionMesh();
                    if (!(mesh instanceof CompiledSectionMesh compiled)) {
                        continue;
                    }
                    SectionDraws draws = compiled.incariDraws;
                    draws.refresh(dispatcher, mesh);
                    BlockPos origin = section.getRenderOrigin();
                    int sectionIndex = -1;
                    int facings = -1;
                    for (ChunkSectionLayer layer : SectionLayers.all()) {
                        int l = Layers.index(layer);
                        GpuBuffer vertexBuffer = draws.vertexBuffers[l];
                        if (!draws.present[l] || vertexBuffer == null) {
                            continue;
                        }
                        if (sectionIndex == -1) {
                            sectionIndex = addSection(origin, section.getVisibility(now, fadeDuration));
                            spritesKnown &= noteSprites(draws, spriteGeneration);
                        }
                        GpuBuffer indexBuffer = draws.indexBuffers[l];
                        IndexType indexType = draws.indexTypes[l];
                        if (indexBuffer == null && draws.indexCount[l] > largestIndexCount) {
                            largestIndexCount = draws.indexCount[l];
                        }
                        Group group;
                        if (layer.translucent() && respectTranslucentOrder) {
                            // Consecutive sections in the same heaps share a group, so the
                            // back-to-front order holds across the whole layer.
                            group = lastTranslucent != null
                                            && lastTranslucent.holds(vertexBuffer, indexBuffer, indexType)
                                    ? lastTranslucent
                                    : newGroup(l, vertexBuffer, indexBuffer, indexType);
                            lastTranslucent = group;
                        } else {
                            group = findGroup(l, vertexBuffer, indexBuffer, indexType);
                        }
                        int runs = runTotal(draws, l);
                        if (runs * 6 != draws.indexCount[l] || layer.translucent() || !culling) {
                            group.add(draws.indexCount[l], draws.firstIndex[l], draws.baseVertex[l], sectionIndex);
                            continue;
                        }
                        if (facings == -1) {
                            facings = Facings.visible(
                                    (float) (camera.x - origin.getX()), (float) (camera.y - origin.getY()), (float)
                                            (camera.z - origin.getZ()));
                        }
                        addRuns(group, draws, l, facings, sectionIndex);
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }
        AnimatedSprites.terrainFrame(seenSprites, spritesKnown);
        return finish(modelView, respectTranslucentOrder, largestIndexCount);
    }

    /** All quads of a layer's runs by facing; 0 where the game's compiler meshed it. */
    private static int runTotal(SectionDraws draws, int layer) {
        int total = 0;
        for (int run = 0; run < Facings.COUNT; run++) {
            total += draws.run(layer, run);
        }
        return total;
    }

    /**
     * Adds a layer's runs whose facing the camera may see, runs next to each other as one command.
     * The indices are the game's sequential quads, so a range starting {@code q} quads into the
     * section is drawn from index {@code 6 q} with the section's base vertex.
     */
    private void addRuns(Group group, SectionDraws draws, int layer, int facings, int sectionIndex) {
        int[] runs = draws.facingRuns;
        if (runs == null) {
            return;
        }
        int count = Facings.visibleRanges(runs, layer * Facings.COUNT, facings, ranges);
        for (int i = 0; i < count; i++) {
            group.add(
                    ranges[i * 2 + 1] * 6,
                    draws.firstIndex[layer] + ranges[i * 2] * 6,
                    draws.baseVertex[layer],
                    sectionIndex);
        }
    }

    /** Adds the section's animated sprites to the frame's; false if it doesn't know them. */
    private boolean noteSprites(SectionDraws draws, int generation) {
        long[] sprites = draws.animatedSprites;
        if (sprites == null || draws.spriteGeneration != generation) {
            return false;
        }
        if (sprites.length > seenSprites.length) {
            seenSprites = java.util.Arrays.copyOf(seenSprites, sprites.length);
        }
        for (int i = 0; i < sprites.length; i++) {
            seenSprites[i] |= sprites[i];
        }
        return true;
    }

    private int addSection(BlockPos origin, float visibility) {
        if (sectionBytes.remaining() < SECTION_BYTES) {
            sectionBytes = grow(sectionBytes, SECTION_BYTES);
        }
        // The game's ChunkSectionInfo in std140: an ivec3 and a float.
        sectionBytes
                .putInt(origin.getX())
                .putInt(origin.getY())
                .putInt(origin.getZ())
                .putFloat(visibility);
        return sections++;
    }

    private Group findGroup(
            int layer, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
        List<Group> layerGroups = groups.get(layer);
        for (int i = 0; i < groupCounts[layer]; i++) {
            Group group = layerGroups.get(i);
            if (group.holds(vertexBuffer, indexBuffer, indexType)) {
                return group;
            }
        }
        return newGroup(layer, vertexBuffer, indexBuffer, indexType);
    }

    private Group newGroup(
            int layer, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType) {
        List<Group> layerGroups = groups.get(layer);
        int index = groupCounts[layer]++;
        if (index == layerGroups.size()) {
            layerGroups.add(new Group());
        }
        Group group = layerGroups.get(index);
        group.reset(vertexBuffer, indexBuffer, indexType);
        return group;
    }

    /** Writes the commands and section data to the GPU and hands the game its draw list. */
    @SuppressWarnings("deprecation") // the blocks atlas by the name the game itself still uses
    private ChunkSectionsToRender finish(Matrix4fc modelView, boolean respectTranslucentOrder, int largestIndexCount) {
        int commands = 0;
        for (int l = 0; l < LAYERS.length; l++) {
            for (int g = 0; g < groupCounts[l]; g++) {
                commands += groups.get(l).get(g).count;
            }
        }
        commandBytes.clear();
        if (commandBytes.capacity() < commands * COMMAND_BYTES) {
            commandBytes = ByteBuffer.allocateDirect(Integer.highestOneBit(Math.max(1, commands) * COMMAND_BYTES) * 2)
                    .order(ByteOrder.nativeOrder());
        }
        GpuBuffer commandTarget = commandBuffer(commandBytes.capacity());
        EnumMap<ChunkSectionLayer, List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect>> draws =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : LAYERS) {
            int l = Layers.index(layer);
            List<ChunkSectionsToRender.GpuMultiDrawIndexedIndirect> layerDraws = new ArrayList<>(groupCounts[l]);
            boolean backToFront = layer.translucent() && respectTranslucentOrder;
            for (int i = 0; i < groupCounts[l]; i++) {
                // The game reverses the translucent groups and their draws: the visible sections
                // come nearest first, and translucent geometry is drawn farthest first.
                Group group = groups.get(l).get(backToFront ? groupCounts[l] - 1 - i : i);
                int offset = commandBytes.position();
                group.write(commandBytes, backToFront);
                GpuBuffer indexBuffer = group.indexBuffer;
                layerDraws.add(new ChunkSectionsToRender.GpuMultiDrawIndexedIndirect(
                        group.vertexBuffer().slice(),
                        indexBuffer == null ? null : indexBuffer.slice(),
                        group.indexType,
                        commandTarget.slice(offset, (long) group.count * COMMAND_BYTES),
                        group.count));
            }
            draws.put(layer, layerDraws);
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (commandBytes.position() > 0) {
            encoder.writeToBuffer(commandTarget.slice(0, commandBytes.position()), commandBytes.flip());
        }
        GpuBuffer sectionTarget = sectionBuffer(sectionBytes.capacity());
        GpuBufferSlice sectionSlice = sectionTarget.slice(0, sectionBytes.position());
        if (sectionBytes.position() > 0) {
            encoder.writeToBuffer(sectionSlice, sectionBytes.flip());
        }
        GpuTextureView atlas = Minecraft.getInstance()
                .getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS)
                .getTextureView();
        GpuBufferSlice terrainTransform = RenderSystem.getDynamicUniforms()
                .writeTerrainTransform(modelView, atlas.getWidth(0), atlas.getHeight(0));
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        if (largestIndexCount != 0) {
            autoIndices.requestIndexCount(largestIndexCount);
        }
        return new ChunkSectionsToRender.DrawIndirect(terrainTransform, draws, largestIndexCount, sectionSlice);
    }

    private GpuBuffer commandBuffer(int bytes) {
        GpuBuffer buffer = commandBuffer;
        int size = Math.max(bytes, COMMAND_BYTES);
        if (buffer == null || buffer.size() < size) {
            if (buffer != null) {
                retired.add(buffer);
            }
            buffer = RenderSystem.getDevice()
                    .createBuffer(
                            () -> "Incari terrain commands",
                            GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST,
                            size);
            commandBuffer = buffer;
        }
        return buffer;
    }

    private GpuBuffer sectionBuffer(int bytes) {
        GpuBuffer buffer = sectionBuffer;
        int size = Math.max(bytes, SECTION_BYTES);
        if (buffer == null || buffer.size() < size) {
            if (buffer != null) {
                retired.add(buffer);
            }
            buffer = RenderSystem.getDevice()
                    .createBuffer(
                            () -> "Incari terrain sections", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
            sectionBuffer = buffer;
        }
        return buffer;
    }

    /** A copy of {@code buffer} with room for at least {@code more} bytes past its position. */
    private static ByteBuffer grow(ByteBuffer buffer, int more) {
        int capacity = Math.max(buffer.capacity() * 2, buffer.position() + more + 1024);
        ByteBuffer grown = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        grown.put(buffer.flip());
        return grown;
    }

    /** The draws from one vertex heap (and index heap) of a layer. */
    private static final class Group {

        private @Nullable GpuBuffer vertexBuffer;

        @Nullable
        GpuBuffer indexBuffer;

        @Nullable
        IndexType indexType;
        /** Index count, first index, base vertex and section index of each command. */
        private int[] commands = new int[4 * 64];

        int count;

        void reset(GpuBuffer vertices, @Nullable GpuBuffer indices, @Nullable IndexType type) {
            vertexBuffer = vertices;
            indexBuffer = indices;
            indexType = type;
            count = 0;
        }

        @SuppressWarnings("ReferenceEquality") // the same heaps, not equal ones
        boolean holds(GpuBuffer vertices, @Nullable GpuBuffer indices, @Nullable IndexType type) {
            return vertices == vertexBuffer && indices == indexBuffer && type == indexType;
        }

        GpuBuffer vertexBuffer() {
            GpuBuffer buffer = vertexBuffer;
            if (buffer == null) {
                throw new IllegalStateException("A draw group without its vertex heap");
            }
            return buffer;
        }

        void add(int indexCount, int firstIndex, int baseVertex, int section) {
            if ((count + 1) * 4 > commands.length) {
                commands = java.util.Arrays.copyOf(commands, commands.length * 2);
            }
            int at = count++ * 4;
            commands[at] = indexCount;
            commands[at + 1] = firstIndex;
            commands[at + 2] = baseVertex;
            commands[at + 3] = section;
        }

        /** Writes the commands as the game's {@code IndexedDraw}s, last first if {@code reversed}. */
        void write(ByteBuffer out, boolean reversed) {
            for (int i = 0; i < count; i++) {
                int at = (reversed ? count - 1 - i : i) * 4;
                out.putInt(commands[at])
                        .putInt(1)
                        .putInt(commands[at + 1])
                        .putInt(commands[at + 2])
                        .putInt(commands[at + 3]);
            }
        }
    }
}
