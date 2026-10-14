package incari.client.world;

import com.mojang.logging.LogUtils;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A block's biome while a chunk's surface is built, without the biome blur's hashing where it can't
 * change the answer. The game finds a block's biome among the eight biome cells around it, picking
 * one by distances it jitters with a hash of the world seed and each cell's position, and returns
 * that cell's biome. When all eight cells hold the same biome, every pick returns it: the hashing
 * decides nothing. Surface rules ask for the biome of nearly every block they place (the
 * underground rule asks whether it's a sulfur cave), and most cells are surrounded by their own
 * biome, so this answers those from the eight cells' biomes alone, read once per group of 4x4x4
 * blocks that share them, and asks the game for the rest.
 *
 * <p>Biome cells don't change while a chunk's surface is built (they were set two steps before, and
 * its neighbours' earlier still), so one chunk's answers are kept for that chunk only. One chunk,
 * one thread; made per chunk.
 */
public final class BiomeCells implements Function<BlockPos, Holder<Biome>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A group whose eight cells hold more than one biome: the game's blur decides. */
    private static final Object MIXED = new Object();

    private static boolean enabled;
    private static boolean verify;
    private static long checked;
    private static long differed;

    private final BiomeManager biomes;
    // The groups this chunk's blocks fall in: a box of (parent cell) positions, filled as asked.
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final @Nullable Object[] groups;

    private BiomeCells(BiomeManager biomes, int minBlockX, int minBlockY, int minBlockZ, int maxBlockY) {
        this.biomes = biomes;
        // A block's group is the cell at ((block - 2) >> 2), as the blur measures from.
        this.minX = (minBlockX - 2) >> 2;
        this.minY = (minBlockY - 2) >> 2;
        this.minZ = (minBlockZ - 2) >> 2;
        this.sizeX = ((minBlockX + 15 - 2) >> 2) - minX + 1;
        this.sizeY = ((maxBlockY - 2) >> 2) - minY + 1;
        this.sizeZ = ((minBlockZ + 15 - 2) >> 2) - minZ + 1;
        this.groups = new Object[sizeX * sizeY * sizeZ];
    }

    /** Off unless the page turns it on ({@code ?biomecells=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Also asks the game each time and logs where the answers differ ({@code ?biomecells=verify}). */
    public static void setVerify(boolean on) {
        verify = on;
    }

    /**
     * Hook ({@code MaterialSystem.buildSurface}): the biome lookup the surface rules use for the chunk
     * whose blocks start at {@code minBlockX}, {@code minBlockZ}, between heights {@code minBlockY}
     * and {@code maxBlockY}; the game's own lookup when off.
     */
    public static Function<BlockPos, Holder<Biome>> forChunk(
            BiomeManager biomes, int minBlockX, int minBlockY, int minBlockZ, int maxBlockY) {
        if (!enabled) {
            return biomes::getBiome;
        }
        return new BiomeCells(biomes, minBlockX, minBlockY, minBlockZ, maxBlockY);
    }

    @Override
    public Holder<Biome> apply(BlockPos pos) {
        Holder<Biome> biome = biomeAt(pos);
        if (verify) {
            verify(pos, biome);
        }
        return biome;
    }

    @SuppressWarnings({"unchecked", "ReferenceEquality"}) // Groups hold biome holders, MIXED or nothing.
    private Holder<Biome> biomeAt(BlockPos pos) {
        int cellX = (pos.getX() - 2) >> 2;
        int cellY = (pos.getY() - 2) >> 2;
        int cellZ = (pos.getZ() - 2) >> 2;
        int x = cellX - minX;
        int y = cellY - minY;
        int z = cellZ - minZ;
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            // Outside the chunk (a rule may look around): the game's way.
            return biomes.getBiome(pos);
        }
        int index = (y * sizeZ + z) * sizeX + x;
        // Null: the group's eight cells haven't been read yet.
        Object group = groups[index];
        if (group == null) {
            group = readGroup(cellX, cellY, cellZ);
            groups[index] = group;
        }
        return group == MIXED ? biomes.getBiome(pos) : (Holder<Biome>) group;
    }

    /** The one biome of the eight cells from ({@code x}, {@code y}, {@code z}) up, or {@link #MIXED}. */
    // Holders are unique per registry entry: the same biome is the same holder. Two holders
    // for one biome would only send its blocks to the game's lookup.
    @SuppressWarnings("ReferenceEquality")
    private Object readGroup(int x, int y, int z) {
        Holder<Biome> first = biomes.getNoiseBiomeAtQuart(x, y, z);
        for (int corner = 1; corner < 8; corner++) {
            Holder<Biome> other =
                    biomes.getNoiseBiomeAtQuart(x + ((corner >> 2) & 1), y + ((corner >> 1) & 1), z + (corner & 1));
            if (other != first) {
                return MIXED;
            }
        }
        return first;
    }

    @SuppressWarnings("ReferenceEquality")
    private void verify(BlockPos pos, Holder<Biome> biome) {
        checked++;
        Holder<Biome> game = biomes.getBiome(pos);
        if (game != biome) {
            differed++;
            if (differed <= 10) {
                LOGGER.warn("[Incari] Biome differs at {}: the game's {}, the cells' {}", pos, game, biome);
            }
        }
        if (checked % 1_000_000 == 0) {
            LOGGER.info("[Incari] Biome cells checked {} times, {} different", checked, differed);
        }
    }
}
