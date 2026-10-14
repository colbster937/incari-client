package incari.client.render.mesher;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LightEngine;
import org.jspecify.annotations.Nullable;

/**
 * The light of a section and of the blocks around it, for its snapshot, read a section at a time
 * (ADR 0027): the 27 sections that the snapshot and its border reach, each section's light array
 * looked up once, instead of two light-engine lookups per block (a hash map lookup each, 20,000 a
 * section: in the fly-through's profile about a quarter of a snapshot's time).
 *
 * <p>Each value is the one {@code getLightValue} gives, from the same visible light data the game's
 * own compiler reads ({@code LightEngine.incariVisibleLayer}, a hook):
 * <ul>
 *   <li>block light: the section's array, or 0 where the section has none;
 *   <li>sky light: the section's array; where it has none, the bottom row of the first section
 *       above it that has one (the engine's own rule: light falls through empty sections from the
 *       sky's lowest stored layer), or 15 where no section above has one. Past the top of the
 *       column's data the engine answers 15 without looking; none of those sections hold data, so
 *       the search ends at 15 too.
 * </ul>
 * A dimension without sky light (the engine's dummy listener) reads 0 everywhere, as the dummy does.
 * Any other listener is read a block at a time, as before.
 *
 * <p>Pool task, between frames. Hot path: nothing allocated per section.
 */
final class SnapshotLight {

    private static final byte OWN = 0;
    private static final byte ROW_ABOVE = 1;
    private static final byte FULL = 2;
    private static final byte DARK = 3;

    private final @Nullable DataLayer[] block = new DataLayer[27];
    private final @Nullable DataLayer[] sky = new DataLayer[27];
    private final byte[] skyMode = new byte[27];

    /**
     * Reads the light arrays of the 27 sections around the section at {@code sectionX},
     * {@code sectionY}, {@code sectionZ}; false if either listener isn't one this can read (then
     * read each block's light from the listeners).
     */
    boolean read(
            LayerLightEventListener skyListener,
            LayerLightEventListener blockListener,
            LevelHeightAccessor level,
            int sectionX,
            int sectionY,
            int sectionZ) {
        boolean noSky = skyListener == LayerLightEventListener.DummyLightLayerEventListener.INSTANCE;
        if (!(blockListener instanceof LightEngine<?, ?> blockEngine)) {
            return false;
        }
        LightEngine<?, ?> skyEngine = null;
        if (!noSky) {
            if (!(skyListener instanceof LightEngine<?, ?> engine)) {
                return false;
            }
            skyEngine = engine;
        }
        // The light engine's sections reach one past the top of the build height.
        int topLightSection = level.getMaxSectionY() + 1;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int i = index(dx, dy, dz);
                    int x = sectionX + dx;
                    int y = sectionY + dy;
                    int z = sectionZ + dz;
                    block[i] = blockEngine.incariVisibleLayer(SectionPos.asLong(x, y, z));
                    if (skyEngine == null) {
                        sky[i] = null;
                        skyMode[i] = DARK;
                        continue;
                    }
                    DataLayer own = skyEngine.incariVisibleLayer(SectionPos.asLong(x, y, z));
                    if (own != null) {
                        sky[i] = own;
                        skyMode[i] = OWN;
                        continue;
                    }
                    DataLayer above = null;
                    for (int up = y + 1; up <= topLightSection && above == null; up++) {
                        above = skyEngine.incariVisibleLayer(SectionPos.asLong(x, up, z));
                    }
                    sky[i] = above;
                    skyMode[i] = above != null ? ROW_ABOVE : FULL;
                }
            }
        }
        return true;
    }

    /**
     * Sky light in the high four bits and block light in the low four, at {@code x}, {@code y},
     * {@code z} relative to the section's first block (-16 to 31 each). Hot path.
     */
    byte at(int x, int y, int z) {
        int i = index(x >> 4, y >> 4, z >> 4);
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        DataLayer blockLayer = block[i];
        int blockValue = blockLayer == null ? 0 : blockLayer.get(lx, ly, lz);
        int skyValue =
                switch (skyMode[i]) {
                    case OWN -> layer(sky[i]).get(lx, ly, lz);
                    case ROW_ABOVE -> layer(sky[i]).get(lx, 0, lz);
                    case FULL -> 15;
                    default -> 0;
                };
        return (byte) (skyValue << 4 | blockValue);
    }

    private static int index(int dx, int dy, int dz) {
        return ((dy + 1) * 3 + (dz + 1)) * 3 + (dx + 1);
    }

    private static DataLayer layer(@Nullable DataLayer layer) {
        if (layer == null) {
            throw new IllegalStateException("A sky section read from a layer it didn't find");
        }
        return layer;
    }
}
