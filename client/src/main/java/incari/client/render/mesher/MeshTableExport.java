package incari.client.render.mesher;

import incari.client.render.AnimatedSprites;
import incari.mesher.GameMath;
import incari.mesher.MeshTables;
import incari.mesher.StateFlags;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the mesher's tables from the game (ADR 0023): every block state's flags and shapes,
 * every baked model's quads and how it picks its parts, the fluids' models. Everything is read from
 * the game's own objects and methods, never assumed: a state whose behavior doesn't reduce to what
 * the tables can say is marked unsupported, and a section holding one is meshed by the game.
 *
 * <p>Runs on the thread that first meshes with new models or settings, once. It also checks the
 * mesher's copies of the game's arithmetic ({@link GameMath}) against the game before anything
 * relies on them.
 */
final class MeshTableExport {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeshTableExport.class);
    private static final Direction[] DIRECTIONS = Direction.values();
    /** A source's height, for a fluid's bottom against the block below. */
    private static final float FLUID_SOURCE_HEIGHT = 0.8888889F;

    private final boolean ambientOcclusion;
    private final boolean cutoutLeaves;
    private final BlockStateModelSet models;
    private final FluidStateModelSet fluidModels;

    private final IdentityHashMap<BlockStateModelPart, Integer> parts = new IdentityHashMap<>();
    private final IdentityHashMap<BakedQuad, Integer> quads = new IdentityHashMap<>();
    private final IdentityHashMap<BlockStateModel, Integer> nodes = new IdentityHashMap<>();
    private final IdentityHashMap<BlockStateModel, Boolean> nodeTinted = new IdentityHashMap<>();
    private final List<Boolean> partTinted = new ArrayList<>();
    private final IdentityHashMap<FluidModel, Integer> fluidModelIndex = new IdentityHashMap<>();
    private final Map<List<AABB>, Integer> shapeIds = new HashMap<>();
    private final List<VoxelShape> shapes = new ArrayList<>();
    private final Map<String, Integer> offsetTables = new HashMap<>();
    private final IdentityHashMap<Fluid, Integer> fluidGroups = new IdentityHashMap<>();
    private MeshTables.@Nullable Builder builder;
    private int unsupported;

    private MeshTableExport(
            boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet models, FluidStateModelSet fluidModels) {
        this.ambientOcclusion = ambientOcclusion;
        this.cutoutLeaves = cutoutLeaves;
        this.models = models;
        this.fluidModels = fluidModels;
    }

    /**
     * The tables for these models and settings.
     *
     * @throws IllegalStateException if the mesher's arithmetic disagrees with the game's
     */
    static MeshTables build(
            boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet models, FluidStateModelSet fluidModels) {
        checkArithmetic();
        long start = System.nanoTime();
        MeshTableExport export = new MeshTableExport(ambientOcclusion, cutoutLeaves, models, fluidModels);
        MeshTables tables = export.run();
        LOGGER.info(
                "Mesher tables: {} states ({} meshed by the game), {} parts, {} quads, {} face shapes, in {} ms",
                tables.stateCount(),
                export.unsupported,
                export.parts.size(),
                tables.quadCount(),
                export.shapes.size(),
                (System.nanoTime() - start) / 1_000_000);
        return tables;
    }

    private MeshTables run() {
        int stateCount = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            stateCount = Math.max(stateCount, Block.BLOCK_STATE_REGISTRY.getId(state) + 1);
        }
        MeshTables.Builder b = new MeshTables.Builder(stateCount, ambientOcclusion);
        builder = b;
        shapes.add(Shapes.empty());
        shapes.add(Shapes.block());
        ModelExplorer explorer = new ModelExplorer(this::partIndex);
        int[] offsetProbes = offsetProbePositions();
        List<Integer> skipStates = new ArrayList<>();

        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            int flags = flags(state);

            for (Direction direction : DIRECTIONS) {
                b.faceShape(id, direction.get3DDataValue(), shapeId(state.getFaceOcclusionShape(direction)));
                fluidCover(b, id, state, direction);
            }

            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                b.fluid(id, fluidGroup(fluid.getType()), fluid.getOwnHeight(), fluidModel(fluidModels.get(fluid)));
            }

            if (state.getRenderShape() == RenderShape.MODEL) {
                BlockStateModel model = models.get(state);
                Integer node = nodes.get(model);
                if (node == null) {
                    ModelExplorer.Node explored = explorer.explore(model);
                    node = explored == null ? -1 : addNode(b, explored);
                    nodes.put(model, node);
                    nodeTinted.put(model, explored != null && tinted(explored));
                }
                if (node < 0) {
                    flags |= StateFlags.UNSUPPORTED;
                } else {
                    b.model(id, node);
                    if (Boolean.TRUE.equals(nodeTinted.get(model))) {
                        flags |= StateFlags.TINTED;
                    }
                }
            }

            if (state.hasOffsetFunction()) {
                int table = offsetTable(state, offsetProbes);
                if (table < 0) {
                    flags |= StateFlags.UNSUPPORTED;
                } else {
                    b.offsetTable(id, table);
                }
            }
            int[] seedOffset = seedOffset(state);
            if (seedOffset == null) {
                flags |= StateFlags.UNSUPPORTED;
            } else {
                b.seedOffset(id, seedOffset[0], seedOffset[1], seedOffset[2]);
            }

            if ((flags & StateFlags.SKIP_RULE) != 0) {
                skipStates.add(id);
            }
            if ((flags & StateFlags.UNSUPPORTED) != 0) {
                unsupported++;
            }
            float shade = state.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            b.state(id, flags, state.getLightEmission(), shade);
        }

        b.faceShapes(
                shapes.size(), (x, y) -> !Shapes.joinIsNotEmpty(shapes.get(x), shapes.get(y), BooleanOp.ONLY_FIRST));
        skipClasses(b, skipStates);
        return b.build();
    }

    @SuppressWarnings("deprecation") // the legacy "solid" flag is what fluids read, as in the game
    private int flags(BlockState state) {
        int flags = 0;
        if (state.isAir()) {
            flags |= StateFlags.AIR;
        }
        if (state.getRenderShape() == RenderShape.MODEL) {
            flags |= StateFlags.MODEL;
        }
        if (state.isSolidRender()) {
            flags |= StateFlags.SOLID_RENDER;
        }
        if (state.isLightPermeable()) {
            flags |= StateFlags.LIGHT_PERMEABLE;
        }
        if (state.emissiveRendering()) {
            flags |= StateFlags.EMISSIVE;
        }
        if (state.isSolid()) {
            flags |= StateFlags.LEGACY_SOLID;
        }
        if (state.is(BlockTags.BLOCKS_FLUID_FLOW)) {
            flags |= StateFlags.BLOCKS_FLUID_FLOW;
        }
        Block block = state.getBlock();
        if (block instanceof IceBlock) {
            flags |= StateFlags.ICE;
        }
        if (block instanceof HalfTransparentBlock || block instanceof LeavesBlock) {
            flags |= StateFlags.FLUID_OVERLAY;
        }
        if (block.hasDynamicShape()) {
            flags |= StateFlags.DYNAMIC_SHAPE;
        }
        if (ModelBlockRenderer.forceOpaque(cutoutLeaves, state)) {
            flags |= StateFlags.FORCE_SOLID_LAYER;
        }
        if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            flags |= StateFlags.COLLISION_FULL;
        }
        if (state.getFluidState().getValueOrElse(FlowingFluid.FALLING, false)) {
            flags |= StateFlags.FLUID_FALLING;
        }
        FluidState water = Fluids.WATER.defaultFluidState();
        FluidState empty = Fluids.EMPTY.defaultFluidState();
        for (Direction direction : DIRECTIONS) {
            if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction)) {
                flags |= 1 << (StateFlags.STURDY_SHIFT + direction.get3DDataValue());
            }
            // A fluid face toward a neighbor with no fluid shows unless the block's own shape hides it.
            if (!FluidRenderer.shouldRenderFace(water, state, direction, empty)) {
                flags |= 1 << (StateFlags.HIDES_OWN_FLUID_SHIFT + direction.get3DDataValue());
            }
            if (state.skipRendering(state, direction)) {
                flags |= StateFlags.SKIP_RULE;
            }
        }
        return flags;
    }

    // ---- Face shapes ---------------------------------------------------------------------------

    /** The shape's id: 0 and 1 for the empty and full shapes by identity, as the game compares them. */
    @SuppressWarnings("ReferenceEquality")
    private int shapeId(VoxelShape shape) {
        if (shape == Shapes.empty()) {
            return MeshTables.SHAPE_EMPTY;
        }
        if (shape == Shapes.block()) {
            return MeshTables.SHAPE_FULL;
        }
        return shapeIds.computeIfAbsent(shape.toAabbs(), key -> {
            shapes.add(shape);
            return shapes.size() - 1;
        });
    }

    /**
     * How the state's face toward {@code direction}'s opposite hides a fluid's face toward
     * {@code direction} (the state being the fluid's neighbor there), as the fluid renderer decides it.
     */
    @SuppressWarnings("ReferenceEquality") // identity, as the fluid renderer tests the shapes
    private static void fluidCover(MeshTables.Builder b, int id, BlockState state, Direction direction) {
        VoxelShape occluder = state.getFaceOcclusionShape(direction.getOpposite());
        int d = direction.get3DDataValue();
        if (occluder == Shapes.empty()) {
            b.fluidCover(id, d, MeshTables.FLUID_COVER_NONE, 0.0F);
        } else if (occluder == Shapes.block()) {
            b.fluidCover(id, d, MeshTables.FLUID_COVER_FULL_BLOCK, 1.0F);
        } else if (direction == Direction.UP) {
            boolean covers = Shapes.blockOccludes(Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), occluder, direction);
            b.fluidCover(id, d, MeshTables.FLUID_COVER_PARTIAL, covers ? 1.0F : 0.0F);
        } else if (direction == Direction.DOWN) {
            boolean covers =
                    Shapes.blockOccludes(Shapes.box(0.0, 0.0, 0.0, 1.0, FLUID_SOURCE_HEIGHT, 1.0), occluder, direction);
            b.fluidCover(id, d, MeshTables.FLUID_COVER_PARTIAL, covers ? 1.0F : 0.0F);
        } else {
            // Whether a side of height h is covered changes only at the occluder's own heights: the
            // cover height is the greatest of them (or the full block) at which it's covered.
            float cover = -1.0F;
            List<Double> heights = new ArrayList<>(occluder.getCoords(Direction.Axis.Y));
            heights.add(1.0);
            for (double height : heights) {
                if (height > 0.0
                        && height > cover
                        && Shapes.blockOccludes(Shapes.box(0.0, 0.0, 0.0, 1.0, height, 1.0), occluder, direction)) {
                    cover = (float) height;
                }
            }
            b.fluidCover(id, d, MeshTables.FLUID_COVER_PARTIAL, cover);
        }
    }

    // ---- Skip rules ----------------------------------------------------------------------------

    /**
     * The states whose faces hide from some neighbors by a rule of their block ({@code skipRendering})
     * hide only from each other (glass from the same glass, leaves from leaves, bars from bars):
     * their rules are tabulated among them, with states that behave alike sharing a class.
     */
    private static void skipClasses(MeshTables.Builder b, List<Integer> skipStates) {
        int count = skipStates.size();
        BlockState[] states = new BlockState[count];
        for (int i = 0; i < count; i++) {
            states[i] = Objects.requireNonNull(Block.BLOCK_STATE_REGISTRY.byId(skipStates.get(i)));
        }
        long[][] rows = new long[count][];
        long[][] columns = new long[count][];
        int words = (count * 6 + 63) >> 6;
        for (int i = 0; i < count; i++) {
            rows[i] = new long[words];
            columns[i] = new long[words];
        }
        for (int a = 0; a < count; a++) {
            for (int c = 0; c < count; c++) {
                for (Direction direction : DIRECTIONS) {
                    if (states[a].skipRendering(states[c], direction)) {
                        int rowBit = c * 6 + direction.get3DDataValue();
                        rows[a][rowBit >> 6] |= 1L << rowBit;
                        int columnBit = a * 6 + direction.get3DDataValue();
                        columns[c][columnBit >> 6] |= 1L << columnBit;
                    }
                }
            }
        }
        Map<String, Integer> classes = new HashMap<>();
        int[] classOf = new int[count];
        List<Integer> representatives = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String key = Arrays.toString(rows[i]) + Arrays.toString(columns[i]);
            Integer known = classes.get(key);
            if (known == null) {
                known = representatives.size();
                classes.put(key, known);
                representatives.add(i);
            }
            classOf[i] = known;
            b.skipClass(skipStates.get(i), known);
        }
        b.skipClasses(representatives.size(), (x, y, d) -> {
            int bit = representatives.get(y) * 6 + d;
            return (rows[representatives.get(x)][bit >> 6] & 1L << bit) != 0;
        });
    }

    // ---- Models --------------------------------------------------------------------------------

    private int partIndex(BlockStateModelPart part) {
        Integer known = parts.get(part);
        if (known != null) {
            return known;
        }
        MeshTables.Builder b = builder();
        int[][] byFace = new int[7][];
        for (int face = 0; face < 7; face++) {
            List<BakedQuad> list = part.getQuads(face < 6 ? DIRECTIONS[face] : null);
            byFace[face] = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                byFace[face][i] = quadIndex(b, list.get(i));
            }
        }
        int index = b.part(byFace, part.useAmbientOcclusion());
        parts.put(part, index);
        boolean tinted = false;
        for (int face = 0; face < 7; face++) {
            for (BakedQuad quad : part.getQuads(face < 6 ? DIRECTIONS[face] : null)) {
                tinted |= quad.materialInfo().isTinted();
            }
        }
        partTinted.add(tinted);
        return index;
    }

    private int quadIndex(MeshTables.Builder b, BakedQuad quad) {
        Integer known = quads.get(quad);
        if (known != null) {
            return known;
        }
        float[] positions = new float[12];
        float[] uvs = new float[8];
        for (int v = 0; v < 4; v++) {
            Vector3fc position = quad.position(v);
            positions[v * 3] = position.x();
            positions[v * 3 + 1] = position.y();
            positions[v * 3 + 2] = position.z();
            long packed = quad.packedUV(v);
            uvs[v * 2] = UVPair.unpackU(packed);
            uvs[v * 2 + 1] = UVPair.unpackV(packed);
        }
        BakedQuad.MaterialInfo material = quad.materialInfo();
        Direction shade = material.shadeDirectionOverride();
        int index = b.quad(
                positions,
                uvs,
                quad.direction().get3DDataValue(),
                Layers.index(material.layer()),
                material.tintIndex(),
                shade == null ? -1 : shade.get3DDataValue(),
                material.lightEmission());
        int animated = AnimatedSprites.id(material.sprite());
        if (animated >= 0) {
            b.animatedQuad(index, animated);
        }
        quads.put(quad, index);
        return index;
    }

    private int addNode(MeshTables.Builder b, ModelExplorer.Node node) {
        if (node instanceof ModelExplorer.Node.Fixed fixed) {
            return fixed.parts().length == 1 ? b.singleNode(fixed.parts()[0]) : b.listNode(fixed.parts());
        }
        if (node instanceof ModelExplorer.Node.Weighted weighted) {
            int[] children = new int[weighted.choices().size()];
            for (int i = 0; i < children.length; i++) {
                children[i] = addNode(b, weighted.choices().get(i));
            }
            return b.weightedNode(weighted.weights(), children);
        }
        ModelExplorer.Node.Multipart multipart = (ModelExplorer.Node.Multipart) node;
        int[] children = new int[multipart.children().size()];
        for (int i = 0; i < children.length; i++) {
            children[i] = addNode(b, multipart.children().get(i));
        }
        return b.multipartNode(children);
    }

    /** Whether any of the node's parts, in any variant, has a tinted quad. */
    private boolean tinted(ModelExplorer.Node node) {
        if (node instanceof ModelExplorer.Node.Fixed fixed) {
            for (int part : fixed.parts()) {
                if (partTinted.get(part)) {
                    return true;
                }
            }
            return false;
        }
        List<ModelExplorer.Node> children = node instanceof ModelExplorer.Node.Weighted weighted
                ? weighted.choices()
                : ((ModelExplorer.Node.Multipart) node).children();
        for (ModelExplorer.Node child : children) {
            if (tinted(child)) {
                return true;
            }
        }
        return false;
    }

    // ---- Fluids --------------------------------------------------------------------------------

    /** Fluids that count as the same fluid share a group (water and flowing water). */
    private int fluidGroup(Fluid fluid) {
        Integer known = fluidGroups.get(fluid);
        if (known != null) {
            return known;
        }
        for (Map.Entry<Fluid, Integer> entry : fluidGroups.entrySet()) {
            if (fluid.isSame(entry.getKey())) {
                fluidGroups.put(fluid, entry.getValue());
                return entry.getValue();
            }
        }
        int group = 1 + (int) fluidGroups.values().stream().distinct().count();
        fluidGroups.put(fluid, group);
        return group;
    }

    private int fluidModel(FluidModel model) {
        Integer known = fluidModelIndex.get(model);
        if (known != null) {
            return known;
        }
        float[] sprites = new float[12];
        putSprite(sprites, 0, model.stillMaterial().sprite());
        putSprite(sprites, 4, model.flowingMaterial().sprite());
        if (model.overlayMaterial() != null) {
            putSprite(sprites, 8, model.overlayMaterial().sprite());
        }
        int index = builder()
                .fluidModel(
                        Layers.index(model.layer()),
                        sprites,
                        model.overlayMaterial() != null,
                        model.tintSource() != null);
        builder()
                .animatedFluid(
                        index,
                        AnimatedSprites.id(model.stillMaterial().sprite()),
                        AnimatedSprites.id(model.flowingMaterial().sprite()),
                        model.overlayMaterial() == null
                                ? -1
                                : AnimatedSprites.id(model.overlayMaterial().sprite()));
        fluidModelIndex.put(model, index);
        return index;
    }

    private static void putSprite(float[] sprites, int at, TextureAtlasSprite sprite) {
        sprites[at] = sprite.getU0();
        sprites[at + 1] = sprite.getU1();
        sprites[at + 2] = sprite.getV0();
        sprites[at + 3] = sprite.getV1();
    }

    // ---- Offsets and seeds ---------------------------------------------------------------------

    /**
     * For each nibble of a column's position seed and each of its 16 values, a position (x, then z)
     * whose seed has it: plant offsets are drawn from those nibbles, x from the first, y the second,
     * z the third.
     */
    @SuppressWarnings("deprecation") // the game's own position hash, which offsets are drawn from
    private static int[] offsetProbePositions() {
        int[] positions = new int[3 * 16 * 2];
        boolean[] found = new boolean[48];
        int remaining = 48;
        for (int x = 0; remaining > 0 && x < 100_000; x++) {
            for (int z = 0; z < 16 && remaining > 0; z++) {
                long seed = Mth.getSeed(x, 0, z);
                for (int nibble = 0; nibble < 3; nibble++) {
                    int value = (int) (seed >> (4 * nibble) & 15L);
                    int slot = nibble * 16 + value;
                    if (!found[slot]) {
                        found[slot] = true;
                        positions[slot * 2] = x;
                        positions[slot * 2 + 1] = z;
                        remaining--;
                    }
                }
            }
        }
        if (remaining > 0) {
            throw new IllegalStateException("No position for every offset nibble");
        }
        return positions;
    }

    /** The state's offsets by nibble, checked at random positions; -1 if they don't follow the nibbles. */
    private int offsetTable(BlockState state, int[] probes) {
        float[] table = new float[48];
        for (int slot = 0; slot < 48; slot++) {
            Vec3 offset = state.getOffset(new BlockPos(probes[slot * 2], 0, probes[slot * 2 + 1]));
            int axis = slot / 16;
            table[slot] = (float) (axis == 0 ? offset.x : axis == 1 ? offset.y : offset.z);
        }
        RandomSource random = RandomSource.create(state.hashCode());
        for (int i = 0; i < 64; i++) {
            BlockPos pos = new BlockPos(
                    random.nextInt(60000) - 30000, random.nextInt(300) - 64, random.nextInt(60000) - 30000);
            long seed = GameMath.positionSeed(pos.getX(), 0, pos.getZ());
            Vec3 offset = state.getOffset(pos);
            if ((float) offset.x != table[(int) (seed & 15L)]
                    || (float) offset.y != table[16 + (int) (seed >> 4 & 15L)]
                    || (float) offset.z != table[32 + (int) (seed >> 8 & 15L)]) {
                LOGGER.warn("Mesher: {}'s offset doesn't follow its position seed; the game meshes it", state);
                return -1;
            }
        }
        return offsetTables.computeIfAbsent(
                Arrays.toString(table), key -> builder().offsets(table));
    }

    /** The 27 offsets of a 3x3x3 cube as indices, the center first. */
    private static final int[] OFFSET_ORDER = offsetOrder();

    private static int[] offsetOrder() {
        int[] order = new int[27];
        order[0] = 13;
        for (int i = 0, n = 1; i < 27; i++) {
            if (i != 13) {
                order[n++] = i;
            }
        }
        return order;
    }

    /** Where the state's seed is taken from, relative to it; null if from nowhere nearby. */
    @SuppressWarnings("deprecation") // the game's own position hash, which seeds come from
    private static int @Nullable [] seedOffset(BlockState state) {
        BlockPos[] probes = {new BlockPos(123, 64, -456), new BlockPos(-7001, -30, 9002), new BlockPos(40, 200, 41)};
        // Nearly every state takes its seed from where it is: that's tried first.
        for (int i = 0; i < 27; i++) {
            int dx = OFFSET_ORDER[i] % 3 - 1;
            int dy = OFFSET_ORDER[i] / 3 % 3 - 1;
            int dz = OFFSET_ORDER[i] / 9 - 1;
            boolean all = true;
            for (BlockPos probe : probes) {
                if (state.getSeed(probe) != Mth.getSeed(probe.getX() + dx, probe.getY() + dy, probe.getZ() + dz)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return new int[] {dx, dy, dz};
            }
        }
        LOGGER.warn("Mesher: {} takes its seed from elsewhere; the game meshes it", state);
        return null;
    }

    private MeshTables.Builder builder() {
        MeshTables.Builder b = builder;
        if (b == null) {
            throw new IllegalStateException("Not building");
        }
        return b;
    }

    // ---- The mesher's arithmetic against the game's ----------------------------------------------

    private static boolean arithmeticChecked;

    /** Checks {@link GameMath} against the game once; throws if they disagree anywhere tried. */
    @SuppressWarnings("deprecation") // the game's own position hash, checked against the mesher's
    static synchronized void checkArithmetic() {
        if (arithmeticChecked) {
            return;
        }
        RandomSource probe = RandomSource.create(20260930L);
        for (int i = 0; i < 4096; i++) {
            int x = probe.nextInt(2_000_000) - 1_000_000;
            int y = probe.nextInt(4096) - 2048;
            int z = probe.nextInt(2_000_000) - 1_000_000;
            if (GameMath.positionSeed(x, y, z) != Mth.getSeed(x, y, z)) {
                throw new IllegalStateException("Mesher position seed differs at " + x + " " + y + " " + z);
            }
        }
        RandomSource game = RandomSource.createThreadLocalInstance(0L);
        GameMath.Random ours = new GameMath.Random();
        for (int i = 0; i < 1024; i++) {
            long seed = probe.nextLong();
            game.setSeed(seed);
            ours.setSeed(seed);
            int bound = 1 + probe.nextInt(i < 512 ? 64 : 100_000);
            if (game.nextInt(bound) != ours.nextInt(bound) || game.nextLong() != ours.nextLong()) {
                throw new IllegalStateException("Mesher random differs from the game's for seed " + seed);
            }
        }
        for (int i = 0; i < 65536; i++) {
            double angle = (i - 32768) * 0.00073;
            if (Float.floatToRawIntBits(GameMath.sin(angle)) != Float.floatToRawIntBits(Mth.sin(angle))
                    || Float.floatToRawIntBits(GameMath.cos(angle)) != Float.floatToRawIntBits(Mth.cos(angle))) {
                throw new IllegalStateException("Mesher sine differs from the game's at " + angle);
            }
        }
        for (int i = 0; i < 20000; i++) {
            double y = probe.nextDouble() * 2.0 - 1.0;
            double x = probe.nextDouble() * 2.0 - 1.0;
            if (Double.doubleToRawLongBits(GameMath.atan2(y, x)) != Double.doubleToRawLongBits(Mth.atan2(y, x))) {
                throw new IllegalStateException("Mesher arctangent differs from the game's at " + y + ", " + x);
            }
        }
        arithmeticChecked = true;
    }
}
