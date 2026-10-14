package incari.client.world;

import incari.client.platform.ClientPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * A chunk's blocks read and written while its surface is built, without the per-block overhead of
 * {@link ProtoChunk}'s own calls (Noisium's and Lithium's surface writes, in spirit). Since 26.3 the
 * material system also lays the deepslate band and the ore veins, so it writes about 16,000 blocks a
 * chunk, and reads every block of every column; in the singleplayer benchmark's fly-through, those
 * calls were a quarter of building the surface, which was itself the largest part of generating.
 *
 * <p>Each write does what {@code ProtoChunk.setBlockState} does for a chunk that hasn't reached
 * light initialization (true of every chunk whose surface is built): the section's block, then each
 * heightmap the chunk's status keeps, updated with the new block. What differs is only how: the
 * heightmaps are looked up once for the chunk instead of iterating the status's set for each block,
 * the build height is read once, and the palette's threading check, which can't fail where every
 * thread is one, is left out there. A chunk this doesn't fit (another kind of chunk, a heightmap not
 * made yet, a later status) is written the game's way: {@link #of} returns null for it.
 *
 * <p>One chunk, on the thread building its surface; made per chunk, never shared.
 */
public final class SurfaceWrites {

    private static boolean enabled;

    private final ProtoChunk chunk;
    private final Heightmap[] heightmaps;
    private final int minY;
    private final int maxY;
    private final int minSectionY;
    private final boolean checkThreading;

    private SurfaceWrites(ProtoChunk chunk, Heightmap[] heightmaps, boolean checkThreading) {
        this.chunk = chunk;
        this.heightmaps = heightmaps;
        this.minY = chunk.getMinY();
        this.maxY = chunk.getMaxY();
        this.minSectionY = chunk.getMinSectionY();
        this.checkThreading = checkThreading;
    }

    /** Off unless the page turns it on ({@code ?surface=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Hook ({@code MaterialSystem.buildSurface}, before the column): the writer for {@code chunk},
     * or null to read and write it the game's way.
     */
    public static @Nullable SurfaceWrites of(ChunkAccess chunk) {
        // Exactly a proto-chunk: an imposter wraps a finished chunk, whose writes do more.
        if (!enabled || chunk.getClass() != ProtoChunk.class) {
            return null;
        }
        ChunkStatus status = chunk.getPersistedStatus();
        if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
            return null;
        }
        Heightmap.Types[] types = status.heightmapsAfter().toArray(new Heightmap.Types[0]);
        Heightmap[] heightmaps = new Heightmap[types.length];
        for (int i = 0; i < types.length; i++) {
            // The game primes a missing heightmap on the first write; leave that to it.
            if (!chunk.hasPrimedHeightmap(types[i])) {
                return null;
            }
            heightmaps[i] = chunk.getOrCreateHeightmapUnprimed(types[i]);
        }
        return new SurfaceWrites((ProtoChunk) chunk, heightmaps, !ClientPlatform.oneThread());
    }

    /** The block at the position, as {@code ProtoChunk.getBlockState} gives it. Hot path: no allocation. */
    public BlockState get(BlockPos pos) {
        int y = pos.getY();
        if (y < minY || y > maxY) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        LevelChunkSection section = chunk.getSection((y >> 4) - minSectionY);
        return section.hasOnlyAir()
                ? Blocks.AIR.defaultBlockState()
                : section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }

    /** Sets the block, as {@code ProtoChunk.setBlockState} would here. Hot path: no allocation. */
    public void set(BlockPos pos, BlockState state) {
        int y = pos.getY();
        if (y < minY || y > maxY) {
            return;
        }
        LevelChunkSection section = chunk.getSection((y >> 4) - minSectionY);
        if (section.hasOnlyAir() && state.is(Blocks.AIR)) {
            return;
        }
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        section.setBlockState(localX, y & 15, localZ, state, checkThreading);
        for (Heightmap heightmap : heightmaps) {
            heightmap.update(localX, y, localZ, state);
        }
    }
}
