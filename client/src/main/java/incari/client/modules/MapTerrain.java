package incari.client.modules;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import incari.client.ui.Circles;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The minimap's terrain ({@code HudCanvas.terrain}, ADR 0051): the loaded world seen from above, one
 * texel a block, in a 512 by 512 texture that wraps. A block's texel is at its x and z modulo 512,
 * so walking draws only the chunks coming into view, each into its own 16 by 16 square, and the
 * map's coordinates run past the texture's edge (the sampler repeats). 512 blocks is more than any
 * zoom shows, turned or not.
 *
 * <p>Each texel is the color of the block a map would show there: the top block's map color, grass,
 * leaves and water in their biome's tint, shaded by the slope down from the north-west, and water
 * darker the deeper it is. Where the world has a ceiling (the Nether), the map is a slice at the
 * player's height instead: the floor of the space they're in, walls darkened.
 *
 * <p>Chunks are drawn in ticks, nearest first, at most 1.5 ms a tick, and only while a map is
 * drawn. A drawn chunk is drawn again only once the game marks it changed ({@link #sectionChanged}):
 * at most every half second near the player (so building and digging show), every second farther
 * out. Render thread (the game's ticks run there too).
 */
public final class MapTerrain {

    /** The texture's size in texels (blocks). */
    static final int SIZE = 512;

    /** Chunks across the texture. */
    private static final int SLOTS = SIZE / 16;

    /** How far from the middle, in chunks, the map can reach: 256 blocks across, turned. */
    private static final int REACH = 13;

    /** Ticks without a draw after which the map stops drawing chunks. */
    private static final int IDLE_TICKS = 40;

    /** The time a tick may spend drawing chunks. */
    private static final long BUDGET_NANOS = 1_500_000;

    /** A changed chunk within one of the player's is drawn again at most this often, in ticks. */
    private static final int NEAR_REFRESH = 10;

    /** A changed chunk farther out, in ticks. */
    private static final int FAR_REFRESH = 20;

    /** How much lighter or darker a block's color gets per block of slope, up to four. */
    private static final float SLOPE_SHADE = 0.075F;

    /**
     * Every color a little darker: the map sits on a dark glass frame, and snow and sand at full
     * white glare beside it.
     */
    private static final float TONE = 0.9F;

    /** A slot that holds no chunk. */
    private static final long NONE = Long.MIN_VALUE;

    /** A slot whose chunk isn't loaded: drawn clear, looked for again each tick. */
    private static final byte MISSING = 1;

    /** A slot whose chunk is drawn. */
    private static final byte DRAWN = 2;

    /** Offsets from the middle chunk within {@link #REACH}, nearest first, as dx, dz pairs. */
    private static final int[] SPIRAL = spiral();

    private static final long[] slotChunk = new long[SLOTS * SLOTS];
    private static final byte[] slotState = new byte[SLOTS * SLOTS];
    private static final int[] slotDrawnAt = new int[SLOTS * SLOTS];
    /** Each drawn slot's colors' hash: a chunk drawn again the same isn't uploaded or recorded again. */
    private static final int[] slotHash = new int[SLOTS * SLOTS];
    /** Whether each drawn slot's chunk has changed since ({@link #sectionChanged}). */
    private static final boolean[] slotChanged = new boolean[SLOTS * SLOTS];

    private static @Nullable GpuTexture texture;
    private static @Nullable GpuTextureView view;
    private static @Nullable NativeImage staging;
    private static @Nullable ClientLevel level;
    /** {@link #level}'s dimension's id, made once. */
    private static String dimension = "";

    private static int ticks;
    private static int drawnAt = -IDLE_TICKS;
    private static int centerX;
    private static int centerZ;
    private static int reach = REACH;
    /** In a world with a ceiling, the height the map is sliced at, and where the slice was last drawn. */
    private static int sliceY;

    private static int slicedAt = Integer.MIN_VALUE;
    private static int refreshCursor;

    /** One column's height and the height of each block's north and west neighbours, while drawing a chunk. */
    private static final int[] heights = new int[18 * 18];

    private static final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    static {
        Arrays.fill(slotChunk, NONE);
    }

    private MapTerrain() {}

    /** From {@code IncariModules}, once a client tick: draws chunks while the map is shown. */
    @SuppressWarnings("ReferenceEquality") // Another level object is another dimension or world.
    static void tick(Minecraft minecraft) {
        ticks++;
        ClientLevel current = minecraft.level;
        if (current == null || ticks - drawnAt > IDLE_TICKS || texture == null) {
            return;
        }
        if (current != level) {
            // Another dimension (or world): what's drawn is of the last one.
            forget();
            level = current;
            dimension = current.dimension().identifier().toString();
        }
        if (current.dimensionType().hasCeiling() && minecraft.player != null) {
            sliceY = minecraft.player.getBlockY() + 1;
            if (Math.abs(sliceY - slicedAt) >= 3) {
                // Up or down a floor: every chunk's slice is of the wrong floor.
                slicedAt = sliceY;
                for (int i = 0; i < slotState.length; i++) {
                    if (slotState[i] == DRAWN) {
                        slotChanged[i] = true;
                        slotDrawnAt[i] = Integer.MIN_VALUE / 2;
                    }
                }
            }
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        // New chunks, nearest first.
        int limit = SPIRAL.length / 2;
        for (int i = 0; i < limit; i++) {
            int dx = SPIRAL[2 * i];
            int dz = SPIRAL[2 * i + 1];
            if (Math.max(Math.abs(dx), Math.abs(dz)) > reach) {
                continue;
            }
            int chunkX = centerX + dx;
            int chunkZ = centerZ + dz;
            int slot = slot(chunkX, chunkZ);
            long key = ChunkPos.pack(chunkX, chunkZ);
            boolean stale = slotChunk[slot] != key;
            if (!stale && slotState[slot] == DRAWN) {
                continue;
            }
            LevelChunk chunk = current.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                if (stale || slotState[slot] != MISSING) {
                    clear(slot, chunkX, chunkZ, key);
                }
                continue;
            }
            draw(current, chunk, slot, key);
            if (System.nanoTime() > deadline) {
                return;
            }
        }
        // Changed chunks, from where the last tick stopped: those by the player sooner. The clock
        // only after a redraw: in the browser each reading is a call out to the page.
        for (int step = 0; step < limit; step++) {
            refreshCursor = (refreshCursor + 1) % limit;
            int dx = SPIRAL[2 * refreshCursor];
            int dz = SPIRAL[2 * refreshCursor + 1];
            int ring = Math.max(Math.abs(dx), Math.abs(dz));
            if (ring <= reach
                    && refresh(current, centerX + dx, centerZ + dz, ring <= 1 ? NEAR_REFRESH : FAR_REFRESH)
                    && System.nanoTime() > deadline) {
                return;
            }
        }
    }

    /**
     * Hook ({@code LevelExtractor.setSectionDirty}): a section of chunk {@code sectionX, sectionZ}
     * changed (a block, its light, the chunk loaded), so its column may look different from above.
     * Called for every section a block change touches: a lookup and a flag.
     */
    public static void sectionChanged(int sectionX, int sectionZ) {
        int slot = slot(sectionX, sectionZ);
        if (slotChunk[slot] == ChunkPos.pack(sectionX, sectionZ)) {
            slotChanged[slot] = true;
        }
    }

    /**
     * Draws the chunk again if it changed and was drawn more than {@code age} ticks ago; true if
     * it was.
     */
    private static boolean refresh(ClientLevel current, int chunkX, int chunkZ, int age) {
        int slot = slot(chunkX, chunkZ);
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (!slotChanged[slot]
                || slotChunk[slot] != key
                || slotState[slot] != DRAWN
                || ticks - slotDrawnAt[slot] < age) {
            return false;
        }
        LevelChunk chunk = current.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            clear(slot, chunkX, chunkZ, key);
        } else {
            draw(current, chunk, slot, key);
        }
        return true;
    }

    /**
     * The world map's, each tick it's open: keeps chunks around {@code x, z} being drawn (and so
     * remembered) as if the minimap were, out as far as the map reaches.
     */
    static void keepDrawing(double x, double z) {
        view();
        drawnAt = ticks;
        centerX = (int) Math.floor(x) >> 4;
        centerZ = (int) Math.floor(z) >> 4;
        reach = REACH;
    }

    /** The player left the world: nothing drawn is theirs any more. */
    static void leave() {
        forget();
        level = null;
    }

    private static void forget() {
        Arrays.fill(slotChunk, NONE);
        Arrays.fill(slotState, (byte) 0);
        Arrays.fill(slotChanged, false);
        slicedAt = Integer.MIN_VALUE;
        GpuTexture current = texture;
        if (current != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(current, new Vector4f(0.0F));
        }
    }

    /**
     * Draws the terrain around {@code centerX, centerZ} into the square {@code size} GUI units at
     * {@code x, y} ({@code HudCanvas.terrain}), and keeps the chunks around it drawn.
     */
    static void draw(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int size,
            boolean round,
            double centerX,
            double centerZ,
            float blocksAcross,
            float rotation) {
        GpuTextureView current = view();
        drawnAt = ticks;
        MapTerrain.centerX = (int) Math.floor(centerX) >> 4;
        MapTerrain.centerZ = (int) Math.floor(centerZ) >> 4;
        // The chunks the map's corners reach, turned, and one more.
        reach = Math.min(REACH, (int) Math.ceil(blocksAcross * 0.71F / 16.0F) + 1);
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        GpuSampler sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        ScreenRectangle bounds = new ScreenRectangle(x, y, size, size).transformMaxBounds(pose);
        float scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        float poseScale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        Minecraft.getInstance()
                .gameRenderer
                .gameRenderState()
                .guiRenderState
                .addGuiElement(new Mesh(
                        pose,
                        TextureSetup.singleTexture(current, sampler),
                        x + size / 2.0F,
                        y + size / 2.0F,
                        size / 2.0F,
                        round,
                        // Where the middle is in the texture, from the world's coordinates modulo its size:
                        // a float holds that exactly, where it would lose blocks at the world's edges.
                        (float) (floorMod(centerX, SIZE) / SIZE),
                        (float) (floorMod(centerZ, SIZE) / SIZE),
                        blocksAcross / size / SIZE,
                        (float) Math.toRadians(rotation),
                        Circles.sides(size / 2.0F * scale * poseScale),
                        bounds));
    }

    private static double floorMod(double value, int modulus) {
        double result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    private static GpuTextureView view() {
        GpuTextureView current = view;
        if (current == null) {
            GpuDevice device = RenderSystem.getDevice();
            GpuTexture made = device.createTexture(
                    () -> "Incari minimap",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM,
                    SIZE,
                    SIZE,
                    1,
                    1);
            device.createCommandEncoder().clearColorTexture(made, new Vector4f(0.0F));
            texture = made;
            current = device.createTextureView(made);
            view = current;
        }
        return current;
    }

    private static int slot(int chunkX, int chunkZ) {
        return (chunkX & (SLOTS - 1)) + (chunkZ & (SLOTS - 1)) * SLOTS;
    }

    private static NativeImage staging() {
        NativeImage current = staging;
        if (current == null) {
            current = new NativeImage(16, 16, true);
            staging = current;
        }
        return current;
    }

    /** Clears a slot's square: its chunk isn't loaded. */
    private static void clear(int slot, int chunkX, int chunkZ, long key) {
        NativeImage image = staging();
        image.fillRect(0, 0, 16, 16, 0);
        upload(image, chunkX, chunkZ);
        slotChunk[slot] = key;
        slotState[slot] = MISSING;
        slotChanged[slot] = false;
    }

    private static void upload(NativeImage image, int chunkX, int chunkZ) {
        GpuTexture current = texture;
        if (current != null) {
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToTexture(current, image, 0, 0, (chunkX & (SLOTS - 1)) * 16, (chunkZ & (SLOTS - 1)) * 16);
        }
    }

    /** Draws a chunk's 256 columns into its slot. */
    private static void draw(ClientLevel current, LevelChunk chunk, int slot, long key) {
        ChunkPos chunkPos = chunk.getPos();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        boolean ceiling = current.dimensionType().hasCeiling();
        int minY = current.getMinY();
        NativeImage image = staging();
        // Heights of the chunk's columns and of the row north and column west of it (from the
        // neighbours where they're loaded, else the chunk's own edge), for the slopes' shading.
        LevelChunk north = current.getChunkSource().getChunk(chunkPos.x(), chunkPos.z() - 1, ChunkStatus.FULL, false);
        LevelChunk west = current.getChunkSource().getChunk(chunkPos.x() - 1, chunkPos.z(), ChunkStatus.FULL, false);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                COLORS[lx + lz * 16] = column(chunk, baseX + lx, baseZ + lz, ceiling, minY);
                FLAT[lx + lz * 16] = columnFlat;
                heights[(lx + 1) + (lz + 1) * 18] = columnY;
            }
        }
        for (int i = 0; i < 16; i++) {
            heights[(i + 1)] = north == null ? heights[(i + 1) + 18] : surface(north, i, 15, ceiling, minY);
            heights[(i + 1) * 18] = west == null ? heights[1 + (i + 1) * 18] : surface(west, 15, i, ceiling, minY);
        }
        int hash = 1;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int color = COLORS[lx + lz * 16];
                if (color != 0 && !FLAT[lx + lz * 16]) {
                    int h = heights[(lx + 1) + (lz + 1) * 18];
                    int slope = (h - heights[(lx + 1) + lz * 18]) + (h - heights[lx + (lz + 1) * 18]);
                    color = shade(color, 1.0F + Math.max(-4, Math.min(4, slope)) * SLOPE_SHADE);
                }
                COLORS[lx + lz * 16] = color;
                hash = 31 * hash + color;
            }
        }
        slotDrawnAt[slot] = ticks;
        slotChanged[slot] = false;
        // Most redraws (the player's own chunks every half second) find nothing changed: then the
        // texture and the world map already have it.
        if (slotChunk[slot] == key && slotState[slot] == DRAWN && slotHash[slot] == hash) {
            return;
        }
        for (int i = 0; i < 256; i++) {
            image.setPixel(i & 15, i >> 4, COLORS[i]);
        }
        upload(image, chunkPos.x(), chunkPos.z());
        // And kept, for the world map.
        MapMemory.record(dimension, chunkPos.x(), chunkPos.z(), COLORS);
        slotChunk[slot] = key;
        slotState[slot] = DRAWN;
        slotHash[slot] = hash;
    }

    /** A chunk's columns' colors while it's drawn, and whether each is shaded already (water, walls). */
    private static final int[] COLORS = new int[256];

    private static final boolean[] FLAT = new boolean[256];

    /** The height of the last column {@link #column} looked at, and whether it's shaded already. */
    private static int columnY;

    private static boolean columnFlat;

    /**
     * A column's color at {@code x, z}, opaque, or 0 (clear) for nothing; its height is left in
     * {@link #columnY}.
     */
    private static int column(LevelChunk chunk, int x, int z, boolean ceiling, int minY) {
        int y = ceiling ? sliceTop(chunk, x, z, minY) : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        boolean wall = false;
        if (y == Integer.MIN_VALUE) {
            // Solid all the way down from the slice: a wall.
            wall = true;
            y = sliceY;
        }
        columnY = y;
        columnFlat = wall;
        if (y < minY) {
            return 0;
        }
        BlockState state = chunk.getBlockState(pos.set(x, y, z));
        MapColor mapColor = state.getMapColor(chunk, pos);
        // Down through what a map shows nothing of: glass, torches, air in a slice.
        for (int steps = 0; mapColor.id == MapColor.NONE.id && y > minY && steps < 64; steps++) {
            y--;
            state = chunk.getBlockState(pos.set(x, y, z));
            mapColor = state.getMapColor(chunk, pos);
        }
        columnY = y;
        if (mapColor.id == MapColor.NONE.id) {
            return 0;
        }
        if (wall) {
            return shade(0xFF000000 | mapColor.col, 0.45F);
        }
        if (mapColor.id == MapColor.WATER.id) {
            int depth = 1;
            for (; depth < 12 && y - depth > minY; depth++) {
                if (chunk.getBlockState(pos.set(x, y - depth, z)).getMapColor(chunk, pos).id != MapColor.WATER.id) {
                    break;
                }
            }
            int water = 0xFF000000 | biome(chunk, x, y, z).getWaterColor();
            // Shallows lighter, the deep darker; flat, so not shaded by slope.
            columnFlat = true;
            return shade(water, (1.08F - depth * 0.045F) * TONE);
        }
        int color;
        if (mapColor.id == MapColor.GRASS.id) {
            color = biome(chunk, x, y, z).getGrassColor(x, z);
        } else if (mapColor.id == MapColor.PLANT.id) {
            color = biome(chunk, x, y, z).getFoliageColor();
        } else {
            color = mapColor.col;
        }
        return shade(0xFF000000 | color, TONE);
    }

    /** The column's surface height only, for a neighbour's edge. */
    private static int surface(LevelChunk chunk, int lx, int lz, boolean ceiling, int minY) {
        ChunkPos chunkPos = chunk.getPos();
        int x = chunkPos.getMinBlockX() + lx;
        int z = chunkPos.getMinBlockZ() + lz;
        int y = ceiling ? sliceTop(chunk, x, z, minY) : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        return y == Integer.MIN_VALUE ? sliceY : y;
    }

    /**
     * Under a ceiling: the top of the floor below the slice's height. From the slice down to the
     * first open block, then down through it; {@code Integer.MIN_VALUE} if solid all the way (a wall).
     */
    private static int sliceTop(LevelChunk chunk, int x, int z, int minY) {
        int y = sliceY;
        int open = Integer.MIN_VALUE;
        for (int steps = 0; steps < 24 && y > minY; steps++, y--) {
            if (chunk.getBlockState(pos.set(x, y, z)).isAir()) {
                open = y;
                break;
            }
        }
        if (open == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        for (int steps = 0; steps < 64 && y > minY; steps++, y--) {
            if (!chunk.getBlockState(pos.set(x, y, z)).isAir()) {
                return y;
            }
        }
        return minY - 1;
    }

    private static Biome biome(LevelChunk chunk, int x, int y, int z) {
        return chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))
                .value();
    }

    /** {@code argb} with each color brighter or darker by {@code factor}, alpha kept. */
    private static int shade(int argb, float factor) {
        int r = Math.min(255, Math.round((argb >> 16 & 0xFF) * factor));
        int g = Math.min(255, Math.round((argb >> 8 & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb & 0xFF) * factor));
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** Offsets within {@link #REACH} chunks, nearest first. */
    private static int[] spiral() {
        int side = 2 * REACH + 1;
        Integer[] order = new Integer[side * side];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(distance(a, side), distance(b, side)));
        int[] offsets = new int[order.length * 2];
        for (int i = 0; i < order.length; i++) {
            offsets[2 * i] = order[i] % side - REACH;
            offsets[2 * i + 1] = order[i] / side - REACH;
        }
        return offsets;
    }

    private static int distance(int index, int side) {
        int dx = index % side - REACH;
        int dz = index / side - REACH;
        return dx * dx + dz * dz;
    }

    /**
     * The map: a fan of triangles around the middle for a round one, a quad for a square, each
     * corner's texture coordinates the world's position there, turned ({@code texelsPer}: texture
     * units a GUI unit).
     */
    private record Mesh(
            Matrix3x2fc pose,
            TextureSetup textureSetup,
            float centerX,
            float centerY,
            float half,
            boolean round,
            float u,
            float v,
            float texelsPer,
            float rotation,
            int sides,
            ScreenRectangle bounds)
            implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer consumer) {
            float cos = (float) Math.cos(rotation);
            float sin = (float) Math.sin(rotation);
            if (!round) {
                vertex(consumer, -half, -half, cos, sin);
                vertex(consumer, -half, half, cos, sin);
                vertex(consumer, half, half, cos, sin);
                vertex(consumer, half, -half, cos, sin);
                return;
            }
            float[] corners = Circles.corners(sides);
            float x0 = half;
            float y0 = 0;
            for (int i = 1; i <= sides; i++) {
                float x1 = corners[2 * i] * half;
                float y1 = corners[2 * i + 1] * half;
                // Wound as the game's GUI quads are: its pipeline culls the other way round.
                vertex(consumer, 0, 0, cos, sin);
                vertex(consumer, x1, y1, cos, sin);
                vertex(consumer, x0, y0, cos, sin);
                vertex(consumer, 0, 0, cos, sin);
                x0 = x1;
                y0 = y1;
            }
        }

        /** A corner {@code sx, sy} from the middle: the world there is the map's turn undone. */
        private void vertex(VertexConsumer consumer, float sx, float sy, float cos, float sin) {
            float east = sx * cos - sy * sin;
            float south = sx * sin + sy * cos;
            consumer.addVertexWith2DPose(pose, centerX + sx, centerY + sy)
                    .setUv(u + east * texelsPer, v + south * texelsPer)
                    .setColor(0xFFFFFFFF);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return null;
        }
    }
}
