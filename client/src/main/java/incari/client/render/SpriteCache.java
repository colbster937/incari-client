package incari.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The vanilla sprites as the build decoded them, with their mip levels (pre-built at build time,
 * ADR 0026). Decoding a sprite's PNG (inflating it, then undoing its filters) and making its mip
 * levels was most of building the atlases at start-up, every start, for the same files. The build
 * runs the game's own decoder and mipmap generator over the vanilla sprites ({@code tools/sprites})
 * and puts the result in the asset pack, which the page keeps in OPFS with the client jar; here a
 * sprite loaded from the vanilla pack is made from that copy instead of from its PNG, and its mip
 * levels too. A resource pack's sprite, an animated one, or anything the build didn't make goes the
 * game's way.
 *
 * <p>The file, little-endian: {@value #MAGIC} as an int, the format version, the sprite count, then
 * per sprite its name (a UTF-8 length-prefixed string), width, height, how many images (level 0 and
 * its mip levels), and each image's RGBA bytes, each level half the last in each direction (at
 * least 1). It's dropped once every sprite in it has been made, or when a second reload starts.
 */
public final class SpriteCache {

    static final int MAGIC = 0x49535031; // "ISP1"
    static final int VERSION = 1;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The vanilla pack's id, as its resources name their source. */
    private static final String VANILLA = "vanilla";

    private static @Nullable Path file;
    private static @Nullable Contents contents;
    private static boolean read;
    private static int reloads;

    /** One sprite's place in the file. */
    record Entry(int width, int height, int levels, int offset) {}

    /** The file's bytes and where each sprite is in them; the images this cache made, until their mip levels are asked for. */
    static final class Contents {
        final ByteBuffer bytes;
        final Map<String, Entry> entries;
        final IdentityHashMap<NativeImage, Entry> made = new IdentityHashMap<>();
        int served;

        Contents(ByteBuffer bytes, Map<String, Entry> entries) {
            this.bytes = bytes;
            this.entries = entries;
        }
    }

    private SpriteCache() {}

    /** The build's file of decoded sprites (the entry point, where the asset pack has one). */
    public static synchronized void install(Path built) {
        file = built;
    }

    /**
     * Hook ({@code SpriteResourceLoader}): the sprite at {@code location} as the build decoded it,
     * if {@code resource} is the vanilla pack's and the build made it; else null, and the game reads
     * the PNG. Asked before the resource is opened, so a hit never inflates the file.
     */
    public static synchronized @Nullable NativeImage image(Identifier location, Resource resource) {
        if (!VANILLA.equals(resource.sourcePackId())) {
            return null;
        }
        Contents cache = contents();
        Entry entry = cache == null ? null : cache.entries.get(location.toString());
        if (cache == null || entry == null) {
            return null;
        }
        NativeImage image = copy(cache.bytes, entry.offset, entry.width, entry.height);
        cache.made.put(image, entry);
        cache.served++;
        return image;
    }

    /**
     * Hook ({@code SpriteContents.increaseMipLevel}): {@code original} and its mip levels up to
     * {@code level}, if this cache made {@code original} and has them; else null, and the game makes
     * them. The levels are what the game's generator gives for that sprite: the build ran it.
     */
    public static synchronized NativeImage @Nullable [] mipLevels(NativeImage original, int level) {
        Contents cache = contents;
        Entry entry = cache == null ? null : cache.made.remove(original);
        if (cache == null || entry == null) {
            return null;
        }
        NativeImage[] images = null;
        if (level < entry.levels) {
            images = new NativeImage[level + 1];
            images[0] = original;
            int offset = entry.offset + entry.width * entry.height * 4;
            for (int i = 1; i <= level; i++) {
                int width = Math.max(1, entry.width >> i);
                int height = Math.max(1, entry.height >> i);
                images[i] = copy(cache.bytes, offset, width, height);
                offset += width * height * 4;
            }
        }
        if (cache.made.isEmpty() && cache.served == cache.entries.size()) {
            // Every sprite in it made, with its levels: what's left of the file is garbage.
            contents = null;
        }
        return images;
    }

    /** Hook (a reload starting): after the first, the file is no help (packs may have changed); it goes. */
    public static synchronized void reloadStarting() {
        if (++reloads > 1) {
            contents = null;
        }
    }

    private static @Nullable Contents contents() {
        if (!read) {
            read = true;
            Path built = file;
            if (built != null && Files.isRegularFile(built)) {
                try {
                    contents = parse(ByteBuffer.wrap(Files.readAllBytes(built)).order(ByteOrder.LITTLE_ENDIAN));
                    LOGGER.info("[Incari] {} vanilla sprites decoded at build time", contents.entries.size());
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Couldn't read the build's decoded sprites ({}); decoding them now", e.toString());
                }
            }
        }
        return contents;
    }

    static Contents parse(ByteBuffer bytes) throws IOException {
        if (bytes.getInt() != MAGIC || bytes.getInt() != VERSION) {
            throw new IOException("not a sprite file of version " + VERSION);
        }
        int count = bytes.getInt();
        Map<String, Entry> entries = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            byte[] name = new byte[bytes.getShort() & 0xFFFF];
            bytes.get(name);
            int width = bytes.getInt();
            int height = bytes.getInt();
            int levels = bytes.get();
            int offset = bytes.position();
            long size = 0;
            for (int level = 0; level < levels; level++) {
                size += (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
            }
            bytes.position(Math.toIntExact(offset + size));
            entries.put(new String(name, StandardCharsets.UTF_8), new Entry(width, height, levels, offset));
        }
        return new Contents(bytes, entries);
    }

    private static NativeImage copy(ByteBuffer bytes, int offset, int width, int height) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        image.getPixelBytes().put(0, bytes, offset, width * height * 4);
        return image;
    }
}
