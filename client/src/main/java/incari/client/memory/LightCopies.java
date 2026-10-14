package incari.client.memory;

import incari.client.platform.ClientPlatform;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The light engine's copies, where every thread is one (ADR 0032). The light engine keeps two maps
 * of each loaded section's light: the one it updates, and a copy the rest of the game reads, so
 * another thread never sees light half updated. After every round of light updates that changed
 * anything (in a fly-through, most frames: light arrives with every chunk) the game copies the
 * whole map over the visible one: for render distance 8 about 8,700 sections, two arrays of 16,384
 * entries, for sky and block light each. In the desktop game's recordings those copies were most of
 * what lived long enough to be collected late; in the page they're what its young collections
 * spend their time on.
 *
 * <p>The copy's content is the updating map's at the swap, and between swaps the updating map only
 * changes at sections the storage notes as changed (a section's light first written, added or
 * removed). So the visible map is brought up to date at those sections only, in place, and is the
 * same map the game's copy would be, layer for layer. The sky map's columns (the top section and
 * lowest section of each) are copied whole, as the game's copy does: a few hundred entries. Only
 * where the game's threads all run on one thread: elsewhere another thread may be reading the
 * visible map while it changes, which is what the copy is for.
 *
 * <p>And light arriving from a server is kept as it arrives: the game copies each section's array
 * out of the packet, which nothing else holds, except over the in-memory connection to the
 * integrated server, where the packet is the server's own object.
 *
 * <p>{@code ?light=game} makes the game's copies, to compare; {@code ?light=verify} brings the map up
 * to date in place and also makes the game's copy, and logs any section where they differ. Game
 * thread.
 */
public final class LightCopies {

    private static final Logger LOGGER = LoggerFactory.getLogger(LightCopies.class);

    private static volatile boolean enabled = true;
    private static boolean verify;
    private static long swaps;
    private static long differences;

    private LightCopies() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Checks every in-place update against the game's copy (development). */
    public static void setVerify(boolean on) {
        verify = on;
    }

    /**
     * Hook ({@code LayerLightSectionStorage.swapSectionMap}): brings {@code visible} up to date with
     * {@code updating} at the {@code changed} sections, in place of copying it; false if the game's
     * copy is needed instead.
     */
    public static boolean updateInPlace(
            DataLayerStorageMap<?> visible, DataLayerStorageMap<?> updating, LongSet changed) {
        if (!enabled || !ClientPlatform.oneThread()) {
            return false;
        }
        Long2ObjectOpenHashMap<DataLayer> from = updating.incariLayers();
        Long2ObjectOpenHashMap<DataLayer> to = visible.incariLayers();
        for (LongIterator i = changed.iterator(); i.hasNext(); ) {
            long section = i.nextLong();
            DataLayer layer = from.get(section);
            if (layer == null) {
                to.remove(section);
            } else {
                to.put(section, layer);
            }
        }
        visible.incariCopyColumnsFrom(updating);
        if (verify) {
            compare(to, updating.copy().incariLayers());
        }
        return true;
    }

    @SuppressWarnings("ReferenceEquality") // the same layer objects the game's copy would hold
    private static void compare(Long2ObjectOpenHashMap<DataLayer> inPlace, Long2ObjectOpenHashMap<DataLayer> copied) {
        swaps++;
        boolean same = inPlace.size() == copied.size();
        for (Long2ObjectMap.Entry<DataLayer> entry : copied.long2ObjectEntrySet()) {
            if (inPlace.get(entry.getLongKey()) != entry.getValue()) {
                same = false;
                break;
            }
        }
        if (!same && differences++ < 10) {
            LOGGER.error("Light maps differ from the game's copy after {} swaps", swaps);
        }
        if (swaps % 1000 == 0) {
            LOGGER.info("Light maps checked: {} swaps, {} different", swaps, differences);
        }
    }

    /**
     * Hook ({@code ClientPacketListener.readSectionList}): a section's light from a packet, for the
     * client to keep: the packet's own array from a remote server, else a copy, as the game makes.
     */
    public static byte[] received(byte[] packetData, boolean memoryConnection) {
        return enabled && !memoryConnection && ClientPlatform.oneThread() ? packetData : packetData.clone();
    }
}
