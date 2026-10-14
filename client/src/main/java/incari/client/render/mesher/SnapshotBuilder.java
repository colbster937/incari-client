package incari.client.render.mesher;

import incari.client.platform.ClientPlatform;
import incari.mesher.MeshTables;
import incari.mesher.Snapshot;
import incari.mesher.StateFlags;
import incari.platform.api.threads.PlatformThreads;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fills a {@link Snapshot} from the game's copy of a section's surroundings (a
 * {@code RenderSectionRegion}): block states and light for the section and two blocks around it,
 * the colors its tinted blocks and fluids take, and the shapes of the few blocks whose shape
 * depends on where they are. It also lists the section's block entities, as the game's compiler
 * does. Copying, not meshing: nothing here looks at a model.
 *
 * <p>Runs where the mesh is asked for: the game's background pool (between frames in the page), or
 * the render thread for a mesh it needs at once. One per thread.
 */
final class SnapshotBuilder {

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Where a long fill gives the page back to the frame (between frames in the page). */
    private final PlatformThreads threads = ClientPlatform.get().threads();

    /**
     * Light read a section's array at a time ({@link SnapshotLight}, ADR 0027); the desktop's
     * {@code -Dincari.mesher.lightByBlock} reads it a block at a time, as before, to compare.
     */
    private static final boolean WHOLE_LIGHT = !Boolean.getBoolean("incari.mesher.lightByBlock");

    /**
     * Whether blocks are read from each section's copy directly (ADR 0034): the region's
     * {@code getBlockState} finds the section and goes through its copy's checks for every block.
     * Page option {@code ?snapshotreads=game} reads them through the region, as before.
     */
    private static volatile boolean directReads = true;

    /** The 27 sections around the one filled: their copied blocks, null where all air. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final @Nullable PalettedContainer<BlockState>[] containers = new PalettedContainer[27];

    private final SnapshotLight sectionLight = new SnapshotLight();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockState[] sectionStates = new BlockState[4096];
    private final int[] tints = new int[16];
    private BlockState lastState = Block.stateById(0);
    private int lastId;

    static void setDirectReads(boolean on) {
        directReads = on;
    }

    /**
     * Reads the 27 sections' copies for {@link #stateAt}; false where they can't be read directly
     * (a debug world's copies make their blocks up).
     */
    private boolean readContainers(RenderSectionRegion region, SectionPos section) {
        if (!directReads) {
            return false;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    SectionCopy copy = region.incariSection(section.x() + dx, section.y() + dy, section.z() + dz);
                    if (copy.incariDebug()) {
                        return false;
                    }
                    containers[(dy + 1) * 9 + (dz + 1) * 3 + dx + 1] = copy.incariStates();
                }
            }
        }
        return true;
    }

    /**
     * The block at section-relative x y z (each -16 to 31), as {@code SectionCopy.getBlockState}
     * gives it: air in a section with none copied. Hot path.
     */
    private BlockState stateAt(int x, int y, int z) {
        PalettedContainer<BlockState> container = containers[((y >> 4) + 1) * 9 + ((z >> 4) + 1) * 3 + (x >> 4) + 1];
        return container == null ? Blocks.AIR.defaultBlockState() : container.get(x & 15, y & 15, z & 15);
    }

    /**
     * Fills {@code s} for the section at {@code section}.
     *
     * @param blockEntities receives the section's block entities, in the game's order
     * @return false if the section holds a state the tables can't describe (the game should mesh
     *     it); true otherwise, and {@code s} is ready unless the section is empty ({@link #empty})
     */
    boolean fill(
            Snapshot s,
            MeshTables tables,
            BlockColors colors,
            FluidStateModelSet fluidModels,
            RenderSectionRegion region,
            SectionPos section,
            Vec3 camera,
            int vertexFormat,
            List<BlockEntity> blockEntities) {
        s.clearLists();
        int ox = section.minBlockX();
        int oy = section.minBlockY();
        int oz = section.minBlockZ();
        s.originX = ox;
        s.originY = oy;
        s.originZ = oz;
        s.cameraX = (float) (camera.x - ox);
        s.cameraY = (float) (camera.y - oy);
        s.cameraZ = (float) (camera.z - oz);
        s.vertexFormat = vertexFormat;
        CardinalLighting cardinal = region.cardinalLighting();
        for (Direction direction : DIRECTIONS) {
            s.cardinalLighting[direction.get3DDataValue()] = cardinal.byFace(direction);
        }

        char[] states = s.states;
        byte[] light = s.light;
        LayerLightEventListener sky = region.getLightEngine().getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockLight = region.getLightEngine().getLayerListener(LightLayer.BLOCK);
        // Each section's light arrays once, not two light-engine lookups a block (ADR 0027).
        boolean wholeLight =
                WHOLE_LIGHT && sectionLight.read(sky, blockLight, region, section.x(), section.y(), section.z());
        boolean supported = true;
        empty = true;
        boolean direct = readContainers(region, section);
        int border = Snapshot.BORDER;
        for (int y = -border; y < 16 + border; y++) {
            // A fill takes a few milliseconds in the page, a slot's worth: past its slot, it goes on
            // in the next rather than hold up a frame. The region is the game's copy of the
            // section's blocks, and light is read live wherever the game meshes too (here, the light
            // arrays as they were when the fill started: the game's own compiler doesn't read light
            // at one instant either).
            threads.pauseIfLate();
            for (int z = -border; z < 16 + border; z++) {
                for (int x = -border; x < 16 + border; x++) {
                    pos.set(ox + x, oy + y, oz + z);
                    BlockState state = direct ? stateAt(x, y, z) : region.getBlockState(pos);
                    int id = id(state);
                    int index = Snapshot.index(x, y, z);
                    states[index] = (char) id;
                    light[index] = wholeLight
                            ? sectionLight.at(x, y, z)
                            : (byte) (sky.getLightValue(pos) << 4 | blockLight.getLightValue(pos));
                    int flags = tables.flags(id);
                    boolean inside = x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16;
                    if (inside) {
                        sectionStates[Snapshot.blockIndex(x, y, z)] = state;
                        if ((flags & StateFlags.AIR) == 0) {
                            empty = false;
                            if ((flags & StateFlags.UNSUPPORTED) != 0) {
                                supported = false;
                            }
                        }
                    }
                    if ((flags & StateFlags.DYNAMIC_SHAPE) != 0) {
                        int sturdy = 0;
                        for (Direction direction : DIRECTIONS) {
                            if (state.isFaceSturdy(region, pos, direction)) {
                                sturdy |= 1 << direction.get3DDataValue();
                            }
                        }
                        s.addDynamic(
                                index,
                                state.isCollisionShapeFullBlock(region, pos),
                                sturdy,
                                state.getShadeBrightness(region, pos));
                    }
                }
            }
        }
        if (!supported || empty) {
            return supported;
        }

        // Tints and block entities, in the order the game's compiler meets the blocks.
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                // A row of tinted blocks (leaves, grass) blends their biomes' colors: the slowest
                // part of a fill, so the checks come often.
                threads.pauseIfLate();
                for (int x = 0; x < 16; x++) {
                    int blockIndex = Snapshot.blockIndex(x, y, z);
                    BlockState state = sectionStates[blockIndex];
                    int id = states[Snapshot.index(x, y, z)];
                    int flags = tables.flags(id);
                    if ((flags & StateFlags.AIR) != 0) {
                        continue;
                    }
                    pos.set(ox + x, oy + y, oz + z);
                    if (state.hasBlockEntity()) {
                        BlockEntity blockEntity = region.getBlockEntity(pos);
                        // A closed chest becomes part of the mesh instead (ADR 0030).
                        if (blockEntity != null && !ChestBaking.bake(blockEntity, s, x, y, z)) {
                            blockEntities.add(blockEntity);
                        }
                    }
                    int modelTints = 0;
                    if ((flags & StateFlags.TINTED) != 0) {
                        List<BlockTintSource> sources = colors.getTintSources(state);
                        modelTints = Math.min(sources.size(), tints.length - 1);
                        for (int i = 0; i < modelTints; i++) {
                            tints[i] = sources.get(i).colorInWorld(state, region, pos);
                        }
                    }
                    boolean fluidTint = tables.fluidTinted(id) && fluidSurfaceMayShow(tables, states, x, y, z);
                    int fluidColor = -1;
                    if (fluidTint) {
                        FluidModel model = fluidModels.get(state.getFluidState());
                        BlockTintSource source = model.tintSource();
                        fluidColor = source == null ? -1 : source.colorInWorld(state, region, pos);
                    }
                    if (modelTints > 0 || fluidTint) {
                        s.addTint(blockIndex, tints, modelTints, fluidTint, fluidColor);
                    }
                }
            }
        }
        return true;
    }

    /** Whether the last section filled has nothing but air in it. */
    boolean empty;

    /** A fluid shows a surface only toward a neighbor that isn't the same fluid. */
    private static boolean fluidSurfaceMayShow(MeshTables tables, char[] states, int x, int y, int z) {
        int group = tables.fluidGroup(states[Snapshot.index(x, y, z)]);
        return tables.fluidGroup(states[Snapshot.index(x, y - 1, z)]) != group
                || tables.fluidGroup(states[Snapshot.index(x, y + 1, z)]) != group
                || tables.fluidGroup(states[Snapshot.index(x, y, z - 1)]) != group
                || tables.fluidGroup(states[Snapshot.index(x, y, z + 1)]) != group
                || tables.fluidGroup(states[Snapshot.index(x - 1, y, z)]) != group
                || tables.fluidGroup(states[Snapshot.index(x + 1, y, z)]) != group;
    }

    /** The state's id; runs of one state are common, so the last lookup is kept. */
    @SuppressWarnings("ReferenceEquality") // block states are unique objects
    private int id(BlockState state) {
        if (state != lastState) {
            lastState = state;
            lastId = Block.getId(state);
        }
        return lastId;
    }
}
