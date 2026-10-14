package incari.client.render;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * A block's biome tint blended as the game blends it, reusing the colours the previous block on its
 * row sampled (ADR 0027).
 *
 * <p>The game tints grass, leaves and water with the average of the colours at the
 * {@code (2r + 1)^2} positions around a block (radius {@code r} is the biome blend option, 2 by
 * default: 25 positions), each one a full-resolution biome lookup. It caches each block's result,
 * but every block it hasn't seen costs all 25 lookups, though its neighbour on the row shared 20 of
 * them. Meshing a leafy section looks up thousands: in the fly-through's profile, a sixth of the
 * main thread's busy time.
 *
 * <p>This keeps the last blend's grid of colours. A block further along the same row (same y and
 * z, x a little larger), for the same resolver and radius, takes the columns it shares and samples
 * only the new ones. The sum is of whole numbers, so its order doesn't change it: the result is the
 * game's to the bit. The grid is dropped whenever the biomes the client holds may have changed: a
 * chunk loaded, unloaded or given new biomes, or the game clearing its tint caches ({@link #changed}).
 *
 * <p>The neighbouring rows sample most of the same positions again, and each sample is a
 * full-resolution biome lookup (eight jittered corners of the coarse biome grid). So the biomes
 * looked up are also kept by position (ADR 0031), in a small table each position has one slot in.
 * A position's biome is fixed while the client's biomes are, so a kept one is the one the lookup
 * would give. A chunk loaded or unloaded drops only the biomes kept in it and the chunks around it
 * (ADR 0034): a lookup reads the coarse biome grid at most a few blocks from its position, never
 * past the next chunk, as the game's own tint caches assume when a chunk loads. Everything else
 * that may change the biomes drops them all.
 *
 * <p>The page only: one thread, between frames. On the desktop the game blends on several compile
 * threads at once and keeps its own way.
 */
public final class TintBlend {

    private static volatile boolean enabled;

    /** Bumped by {@link #changed}: a grid from an earlier count is stale. */
    private static int epoch;

    private static int gridEpoch = -1;
    private static ColorResolver gridResolver = (biome, x, z) -> 0;
    private static int gridRadius;
    private static int gridX;
    private static int gridY;
    private static int gridZ;
    /** The colours of the last blend: {@code [dz * side + dx]}, from its corner at -radius, -radius. */
    private static int[] grid = new int[25];

    private static final BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
    /** What {@link #LEVEL_COLORS} samples, for the blend under way (one shared sampler: hot path, no allocation). */
    private static @Nullable ClientLevel sampleLevel;

    private static ColorResolver sampleResolver = (biome, x, z) -> 0;

    /** A position's colour as the game samples it: its biome's, through the resolver. */
    private static final ColorAt LEVEL_COLORS = (sx, sy, sz) -> {
        ClientLevel level = Objects.requireNonNull(sampleLevel);
        return sampleResolver.getColor(biome(level, sx, sy, sz).value(), sx, sz);
    };

    /** Biome slots kept: a power of two. Two sections' worth of a leafy layer's samples. */
    private static final int BIOME_SLOTS = 1 << 13;

    private static final long[] biomeKeys = new long[BIOME_SLOTS];
    /** The {@link #biomeEpoch} each slot was filled in; a slot from another is empty. */
    private static final int[] biomeEpochs = new int[BIOME_SLOTS];

    /** Bumped when every kept biome may be stale; a chunk's own changes drop only the slots near it. */
    private static int biomeEpoch;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final @Nullable Holder<Biome>[] biomes = new Holder[BIOME_SLOTS];

    private static @Nullable ClientLevel biomeLevel;

    static {
        Arrays.fill(biomeEpochs, -1);
    }

    private TintBlend() {}

    /** {@code level.getBiome(x, y, z)}, kept by position until the client's biomes may change. */
    private static Holder<Biome> biome(ClientLevel level, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int slot = (int) HashCommon.mix(key) & (BIOME_SLOTS - 1);
        Holder<Biome> kept = biomes[slot];
        if (kept != null && biomeEpochs[slot] == biomeEpoch && biomeKeys[slot] == key) {
            return kept;
        }
        sample.set(x, y, z);
        Holder<Biome> biome = level.getBiome(sample);
        biomes[slot] = biome;
        biomeKeys[slot] = key;
        biomeEpochs[slot] = biomeEpoch;
        return biome;
    }

    /**
     * Counts the times the biomes or chunks the client holds may have changed ({@link #changed}):
     * for other caches kept by position ({@code ParticleLight}).
     */
    public static int epoch() {
        return epoch;
    }

    /** On in the page ({@code ?tint=game} leaves it off, to compare); off on the desktop. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Whether {@link #blend} stands in for the game's blending. */
    public static boolean enabled() {
        return enabled;
    }

    /** Hook ({@code ClientLevel.clearTintCaches}): the biomes the client holds may have changed. */
    public static void changed() {
        epoch++;
        biomeEpoch++;
    }

    /**
     * Hook ({@code ClientLevel.onChunkLoaded}, {@code unload}): a chunk's biomes came or went. Kept
     * biomes in it and the chunks around it are dropped; the rest stay.
     */
    public static void chunkChanged(int chunkX, int chunkZ) {
        epoch++;
        for (int slot = 0; slot < BIOME_SLOTS; slot++) {
            if (biomeEpochs[slot] != biomeEpoch) {
                continue;
            }
            long key = biomeKeys[slot];
            if (Math.abs((BlockPos.getX(key) >> 4) - chunkX) <= 1
                    && Math.abs((BlockPos.getZ(key) >> 4) - chunkZ) <= 1) {
                biomeEpochs[slot] = -1;
            }
        }
    }

    /**
     * Hook ({@code ClientLevel.calculateBlockTint}, radius above 0): the game's blend of
     * {@code resolver}'s colours within {@code radius} of {@code pos}. Hot path.
     */
    @SuppressWarnings("ReferenceEquality") // a level is the client's one instance
    public static int blend(ClientLevel level, BlockPos pos, ColorResolver resolver, int radius) {
        if (level != biomeLevel) {
            // Another world: nothing kept from the last one holds.
            biomeLevel = level;
            epoch++;
            biomeEpoch++;
        }
        sampleLevel = level;
        sampleResolver = resolver;
        try {
            return blend(pos.getX(), pos.getY(), pos.getZ(), resolver, radius, LEVEL_COLORS);
        } finally {
            sampleLevel = null;
        }
    }

    /** One sampled position's colour, as the game computes it. */
    @FunctionalInterface
    interface ColorAt {
        int color(int x, int y, int z);
    }

    /** {@link #blend(ClientLevel, BlockPos, ColorResolver, int)} with the samples from {@code colors}; the test's way in. */
    @SuppressWarnings("ReferenceEquality") // resolvers are the game's constants
    static int blend(int x, int y, int z, ColorResolver resolver, int radius, ColorAt colorAt) {
        int side = radius * 2 + 1;
        int step = x - gridX;
        boolean sameRow = gridEpoch == epoch
                && gridResolver == resolver
                && gridRadius == radius
                && gridY == y
                && gridZ == z
                && step >= 0
                && step < side;
        if (grid.length < side * side) {
            grid = new int[side * side];
            sameRow = false;
        }
        int[] colors = grid;
        int firstNew = 0;
        if (sameRow) {
            // Columns the last blend sampled move left by the step; only the rest are sampled.
            for (int dz = 0; dz < side; dz++) {
                int row = dz * side;
                System.arraycopy(colors, row + step, colors, row, side - step);
            }
            firstNew = side - step;
        }
        for (int dz = 0; dz < side; dz++) {
            int sz = z - radius + dz;
            for (int dx = firstNew; dx < side; dx++) {
                colors[dz * side + dx] = colorAt.color(x - radius + dx, y, sz);
            }
        }
        gridEpoch = epoch;
        gridResolver = resolver;
        gridRadius = radius;
        gridX = x;
        gridY = y;
        gridZ = z;
        int totalRed = 0;
        int totalGreen = 0;
        int totalBlue = 0;
        int count = side * side;
        for (int i = 0; i < count; i++) {
            int color = colors[i];
            totalRed += ARGB.red(color);
            totalGreen += ARGB.green(color);
            totalBlue += ARGB.blue(color);
        }
        return ARGB.color(totalRed / count, totalGreen / count, totalBlue / count);
    }
}
