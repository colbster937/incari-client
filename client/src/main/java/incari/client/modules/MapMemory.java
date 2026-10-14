package incari.client.modules;

import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What the minimap has seen ({@link MapTerrain}), for the world map (ADR 0051): every chunk's 256
 * colors as the map drew them, by region of 32 by 32 chunks, for each world and dimension. With the
 * minimap's Remember explored map on, regions are kept in the game directory
 * ({@code incari/map/<world>/<dimension>/<x>.<z>.map}) and read back when the world map or the
 * minimap reaches them again; off, the map remembers only this visit, as many regions as it holds.
 *
 * <p>A region file: {@code IMAP}, a version, a bit for each of its 1024 chunks that's been seen, and
 * each seen chunk's colors as RGB (0, 0, 0 for nothing there: black is stored a step lighter), all
 * deflated at zlib's fastest level. A region seen whole is about 0.8 MB before and some 100-250 KB
 * after. In the browser the game directory is in memory, stored behind (ADR 0011), so neither
 * reading nor writing waits on a disk.
 *
 * <p>Render thread (the game's ticks run there too).
 */
public final class MapMemory {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks a region's side. */
    static final int REGION_CHUNKS = 32;

    /** Blocks a region's side. */
    static final int REGION_BLOCKS = REGION_CHUNKS * 16;

    private static final int MAGIC = 0x494D4150;
    private static final int VERSION = 1;

    /** Regions kept in memory at most: some 1 MB each when seen whole. */
    private static final int KEPT = 24;

    /** Ticks between saves of what changed: half a minute. */
    private static final int SAVE_TICKS = 600;

    /** A region of one dimension. */
    record Key(String dimension, int x, int z) {}

    /** A region's chunks' colors, those seen; a number that goes up as more are. */
    static final class Region {

        final Key key;
        final int[] @Nullable [] chunks = new int[REGION_CHUNKS * REGION_CHUNKS][];
        int revision;
        boolean dirty;
        long usedAt;

        Region(Key key) {
            this.key = key;
        }

        /** Chunk {@code cx, cz} within the region (0 to 31 each), or null if unseen. */
        int @Nullable [] chunk(int cx, int cz) {
            return chunks[cx + cz * REGION_CHUNKS];
        }
    }

    private static final Map<Key, Region> regions = new HashMap<>();
    private static @Nullable Path folder;
    private static boolean remember = true;
    private static long uses;
    private static int ticks;
    /** Whether a half minute's saving is under way ({@link #tick}). */
    private static boolean saving;

    private MapMemory() {}

    /** The minimap's Remember explored map. Turned on, what's been seen this visit is kept from now on. */
    static void setRemember(boolean on) {
        if (on && !remember) {
            Path root = folder;
            for (Region region : regions.values()) {
                // A region made while the map wasn't remembered never read its file: what the file
                // has and this visit hasn't seen joins it first, so saving it doesn't lose that.
                Region saved = root == null ? null : read(root, region.key);
                if (saved != null) {
                    merge(saved, region);
                }
                region.dirty = true;
            }
        }
        remember = on;
    }

    /** Puts {@code from}'s chunks into {@code into} where {@code into} has none. */
    static void merge(Region from, Region into) {
        boolean added = false;
        for (int i = 0; i < into.chunks.length; i++) {
            if (into.chunks[i] == null && from.chunks[i] != null) {
                into.chunks[i] = from.chunks[i];
                added = true;
            }
        }
        if (added) {
            into.revision++;
        }
    }

    /** The player joined {@code world} ({@code Game.world()}); its map is kept under its own folder. */
    static void open(Path gameDirectory, String world) {
        close();
        folder = gameDirectory.resolve("incari").resolve("map").resolve(safe(world));
    }

    /**
     * Deletes {@code world}'s explored map: the game deleted the world, and a new one with its name
     * mustn't find it. Nothing for the world open now.
     */
    static void delete(Path gameDirectory, String world) {
        Path kept = gameDirectory.resolve("incari").resolve("map").resolve(safe(world));
        if (kept.equals(folder) || !Files.isDirectory(kept)) {
            return;
        }
        try (Stream<Path> files = Files.walk(kept)) {
            // Deepest first: each folder is empty by the time it's deleted.
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't delete the explored map {}", kept, e);
        }
    }

    /** The player left: what changed is saved, and the regions let go. */
    static void close() {
        save();
        regions.clear();
        folder = null;
    }

    /**
     * Once a client tick: what changed is saved every half minute, a region a tick. Each region is
     * deflated on the render thread, and several at once would be one long frame.
     */
    static void tick() {
        if (++ticks % SAVE_TICKS == 0) {
            saving = true;
        }
        if (saving) {
            saving = writeOne();
        }
    }

    /** Saves one region that changed; false if none had. */
    private static boolean writeOne() {
        if (!remember) {
            return false;
        }
        for (Region region : regions.values()) {
            if (region.dirty) {
                write(region);
                return true;
            }
        }
        return false;
    }

    /** The map drew chunk {@code chunkX, chunkZ} of {@code dimension}: its 256 colors, row by row. */
    static void record(String dimension, int chunkX, int chunkZ, int[] colors) {
        if (folder == null) {
            return;
        }
        Region region = region(dimension, chunkX >> 5, chunkZ >> 5, true);
        if (region == null) {
            return;
        }
        int index = (chunkX & 31) + (chunkZ & 31) * REGION_CHUNKS;
        int[] kept = region.chunks[index];
        if (kept == null) {
            kept = new int[256];
            region.chunks[index] = kept;
        } else if (java.util.Arrays.equals(kept, colors)) {
            return;
        }
        System.arraycopy(colors, 0, kept, 0, 256);
        region.revision++;
        region.dirty = true;
    }

    /**
     * Region {@code x, z} of {@code dimension}, read from its file if it isn't in memory; null if
     * there's no world open, or nothing seen there and {@code make} is false.
     */
    static @Nullable Region region(String dimension, int x, int z, boolean make) {
        Path root = folder;
        if (root == null) {
            return null;
        }
        Key key = new Key(dimension, x, z);
        Region region = regions.get(key);
        if (region == null) {
            region = remember ? read(root, key) : null;
            if (region == null) {
                if (!make) {
                    return null;
                }
                region = new Region(key);
            }
            // Used now, before the eviction: else the region just read would be the one to go.
            region.usedAt = ++uses;
            regions.put(key, region);
            evict();
        }
        region.usedAt = ++uses;
        return region;
    }

    /** Whether a region of {@code dimension} at {@code x, z} has a file, without reading it. */
    static boolean saved(String dimension, int x, int z) {
        Path root = folder;
        return root != null && remember && Files.isRegularFile(file(root, new Key(dimension, x, z)));
    }

    /** Over {@link #KEPT}: the least lately used goes, saved first if it's to be kept. */
    private static void evict() {
        while (regions.size() > KEPT) {
            Region oldest = null;
            for (Region region : regions.values()) {
                if (oldest == null || region.usedAt < oldest.usedAt) {
                    oldest = region;
                }
            }
            if (oldest == null) {
                return;
            }
            write(oldest);
            regions.remove(oldest.key);
        }
    }

    /** Saves every region that changed, if the map is remembered (also the smoke test's, to check). */
    public static void save() {
        List<Region> all = new ArrayList<>(regions.values());
        for (Region region : all) {
            write(region);
        }
    }

    private static void write(Region region) {
        Path root = folder;
        if (!remember || !region.dirty || root == null) {
            return;
        }
        region.dirty = false;
        Path file = file(root, region.key);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                byte[] seen = new byte[region.chunks.length / 8];
                for (int i = 0; i < region.chunks.length; i++) {
                    if (region.chunks[i] != null) {
                        seen[i >> 3] |= (byte) (1 << (i & 7));
                    }
                }
                out.write(seen);
                byte[] rgb = new byte[256 * 3];
                for (int[] chunk : region.chunks) {
                    if (chunk == null) {
                        continue;
                    }
                    for (int i = 0; i < 256; i++) {
                        int color = chunk[i];
                        // Clear is 0, 0, 0; a black block a step lighter, so it isn't read as clear.
                        int stored = (color >>> 24) == 0 ? 0 : (color & 0xFFFFFF) == 0 ? 0x010101 : color;
                        rgb[3 * i] = (byte) (stored >> 16);
                        rgb[3 * i + 1] = (byte) (stored >> 8);
                        rgb[3 * i + 2] = (byte) stored;
                    }
                    out.write(rgb);
                }
            } finally {
                deflater.end();
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, bytes.toByteArray());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't save the map's region {}", file, e);
        }
    }

    private static @Nullable Region read(Path root, Key key) {
        Path file = file(root, key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream raw = Files.newInputStream(file);
                DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                LOGGER.warn("Not a map region this build reads: {}", file);
                return null;
            }
            byte[] seen = new byte[REGION_CHUNKS * REGION_CHUNKS / 8];
            in.readFully(seen);
            Region region = new Region(key);
            byte[] rgb = new byte[256 * 3];
            for (int i = 0; i < region.chunks.length; i++) {
                if ((seen[i >> 3] & (1 << (i & 7))) == 0) {
                    continue;
                }
                in.readFully(rgb);
                int[] chunk = new int[256];
                for (int j = 0; j < 256; j++) {
                    int color = (rgb[3 * j] & 0xFF) << 16 | (rgb[3 * j + 1] & 0xFF) << 8 | (rgb[3 * j + 2] & 0xFF);
                    chunk[j] = color == 0 ? 0 : 0xFF000000 | color;
                }
                region.chunks[i] = chunk;
            }
            return region;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read the map's region {}", file, e);
            return null;
        }
    }

    private static Path file(Path root, Key key) {
        return root.resolve(safe(key.dimension())).resolve(key.x() + "." + key.z() + ".map");
    }

    /** A name as a folder's: letters, digits, dots and dashes kept, the rest underscores, and a hash. */
    static String safe(String name) {
        StringBuilder safe = new StringBuilder(name.length() + 9);
        for (int i = 0; i < name.length() && i < 64; i++) {
            char c = name.charAt(i);
            boolean plain =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            safe.append(plain ? c : '_');
        }
        return safe.append('-')
                .append(Integer.toHexString(name.hashCode()).toLowerCase(Locale.ROOT))
                .toString();
    }
}
