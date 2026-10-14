package incari.client.particle;

import incari.client.render.TintBlend;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * A particle's light (ADR 0024), as the game reads it at the block the particle is in, but
 * read once for each block rather than once for each particle in it: a fountain of a few thousand
 * particles sits in far fewer blocks. Nothing is allocated (the game made a block position for each
 * particle each frame).
 *
 * <p>Kept from frame to frame (ADR 0031) until something it depends on may have changed: a block or
 * its light (both mark the block's render sections dirty, {@link #changed}), or a chunk loaded or
 * unloaded ({@link TintBlend#epoch}). Until then the game would read the same light and block state
 * again, so a kept answer is its own. A crowded lobby's particles sit in a thousand blocks or more,
 * and reading them all again each frame was a twentieth of its work.
 *
 * <p>Render thread only, where particles are extracted and sections are marked dirty. Hot path.
 */
public final class ParticleLight {

    /** Light the game gives a particle whose chunk isn't loaded: full sky light, no block light. */
    private static final int UNLOADED = 0xF00000;

    /** Blocks kept at most; past it the cache starts over (particles drifting through new blocks). */
    private static final int CAPACITY = 16384;

    private static final Long2IntOpenHashMap CACHE = new Long2IntOpenHashMap(1024);
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    /** Counts {@link #changed}: answers from an earlier count may be stale. */
    private static int changes;

    private static int cachedChanges = -1;
    private static int cachedChunks = -1;
    private static @Nullable ClientLevel cachedLevel;

    /** Whether answers outlive a frame (the page's {@code ?particlelight=frame} keeps them one frame, as before). */
    private static volatile boolean acrossFrames = true;

    private static long cachedFrame = Long.MIN_VALUE;

    static {
        CACHE.defaultReturnValue(-1);
    }

    private ParticleLight() {}

    /** Whether answers are kept from frame to frame, or only within one (ADR 0024's way). */
    public static void setAcrossFrames(boolean on) {
        acrossFrames = on;
    }

    /** Hook ({@code LevelExtractor.setSectionDirty}): a block or its light changed in a section. */
    public static void changed() {
        changes++;
    }

    /** Hook: the light coordinates of a particle at x y z. */
    @SuppressWarnings({"ReferenceEquality", "deprecation"}) // the level itself; the game's own chunk check
    public static int at(ClientLevel level, double x, double y, double z) {
        int chunks = TintBlend.epoch();
        // Within one frame only, the frame told apart by the game time and how far into the tick.
        long frame = acrossFrames
                ? 0
                : level.getGameTime() * 31
                        + Float.floatToRawIntBits(
                                Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (changes != cachedChanges
                || chunks != cachedChunks
                || level != cachedLevel
                || frame != cachedFrame
                || CACHE.size() >= CAPACITY) {
            CACHE.clear();
            cachedChanges = changes;
            cachedChunks = chunks;
            cachedLevel = level;
            cachedFrame = frame;
        }
        int bx = Mth.floor(x);
        int by = Mth.floor(y);
        int bz = Mth.floor(z);
        long key = BlockPos.asLong(bx, by, bz);
        int light = CACHE.get(key);
        if (light == -1) {
            BlockPos pos = POS.set(bx, by, bz);
            light = level.hasChunkAt(pos) ? LightCoordsUtil.getLightCoords(level, pos) : UNLOADED;
            CACHE.put(key, light);
        }
        return light;
    }
}
