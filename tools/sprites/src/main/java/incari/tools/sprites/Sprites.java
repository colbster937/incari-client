package incari.tools.sprites;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The vanilla atlases' sprites, decoded, with the mip levels the game would make of them:
 * what {@code client}'s {@code SpriteCache} reads in the browser instead of the PNGs.
 *
 * <p>Each atlas's sprites are listed by the game's own sprite sources; a sprite that is a single file
 * of its own and not animated is decoded with the game's decoder and given its levels by the game's
 * {@code MipmapGenerator}, with the inputs the game's {@code SpriteContents} would give it (its
 * texture metadata, its transparency). Level 0 is kept as the generator leaves it: for a cut-out
 * sprite the generator fills the colour of its transparent pixels, which the game uploads. Sprites
 * made another way (palette permutations, cut from a sheet) and animated ones are left to the game.
 */
final class Sprites {

    /** The format {@code SpriteCache} reads; the version goes up with any change to either side. */
    static final int MAGIC = 0x49535031; // "ISP1"

    static final int VERSION = 1;

    /** The game's largest mipmap setting: the blocks atlas's sprites get every level up to it. */
    static final int MAX_MIP_LEVELS = 4;

    /** The game's atlases, and how many mip levels each makes (only the blocks atlas has any). */
    private static final Map<Identifier, Integer> ATLASES = new LinkedHashMap<>();

    static {
        ATLASES.put(AtlasIds.BLOCKS, MAX_MIP_LEVELS);
        for (Identifier atlas : List.of(
                AtlasIds.ITEMS,
                AtlasIds.GUI,
                AtlasIds.PARTICLES,
                AtlasIds.PAINTINGS,
                AtlasIds.BANNER_PATTERNS,
                AtlasIds.SHIELD_PATTERNS,
                AtlasIds.CHESTS,
                AtlasIds.SHULKER_BOXES,
                AtlasIds.DECORATED_POT,
                AtlasIds.MAP_DECORATIONS,
                AtlasIds.CELESTIALS)) {
            ATLASES.put(atlas, 0);
        }
    }

    /** One sprite: its name and its images, level 0 first. */
    record Sprite(String name, List<NativeImage> levels) implements AutoCloseable {
        @Override
        public void close() {
            levels.forEach(NativeImage::close);
        }
    }

    private Sprites() {}

    /** Every vanilla sprite this file can carry, from {@code resources} (the vanilla pack alone). */
    static List<Sprite> decode(ResourceManager resources) {
        Map<String, Sprite> sprites = new LinkedHashMap<>();
        ATLASES.forEach((atlas, mipLevels) -> {
            SpriteResourceLoader capture = (location, resource) -> {
                if (!sprites.containsKey(location.toString())) {
                    Sprite sprite = decode(location, resource, mipLevels);
                    if (sprite != null) {
                        sprites.put(sprite.name(), sprite);
                    }
                }
                return null;
            };
            for (SpriteSource.Loader loader :
                    SpriteSourceList.load(resources, atlas).list(resources)) {
                loader.get(capture);
            }
        });
        return new ArrayList<>(sprites.values());
    }

    /** {@code location}'s images, or null if the game must make it itself (animated, unreadable). */
    private static @Nullable Sprite decode(Identifier location, Resource resource, int mipLevels) {
        Optional<TextureMetadataSection> texture;
        try {
            if (resource.metadata().getSection(AnimationMetadataSection.TYPE).isPresent()) {
                return null;
            }
            texture = resource.metadata().getSection(TextureMetadataSection.TYPE);
        } catch (IOException e) {
            return null;
        }
        NativeImage image;
        try (InputStream in = resource.open()) {
            image = NativeImage.read(in);
        } catch (IOException e) {
            return null;
        }
        // As far as the game's stitcher can take this sprite: down to a texel of its smallest side.
        int levels = Math.min(
                mipLevels,
                Mth.log2(Math.min(Integer.lowestOneBit(image.getWidth()), Integer.lowestOneBit(image.getHeight()))));
        NativeImage[] made = MipmapGenerator.generateMipLevels(
                location,
                new NativeImage[] {image},
                levels,
                texture.map(TextureMetadataSection::mipmapStrategy).orElse(MipmapStrategy.AUTO),
                texture.map(TextureMetadataSection::alphaCutoffBias).orElse(0.0F),
                image.computeTransparency());
        return new Sprite(location.toString(), List.of(made));
    }

    /** {@code sprites} in {@code SpriteCache}'s format. */
    static void write(List<Sprite> sprites, OutputStream to) throws IOException {
        DataOutputStream out = new DataOutputStream(to);
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC).putInt(VERSION).putInt(sprites.size());
        out.write(header.array());
        for (Sprite sprite : sprites) {
            byte[] name = sprite.name().getBytes(StandardCharsets.UTF_8);
            NativeImage first = sprite.levels().getFirst();
            ByteBuffer entry = ByteBuffer.allocate(2 + name.length + 9).order(ByteOrder.LITTLE_ENDIAN);
            entry.putShort((short) name.length)
                    .put(name)
                    .putInt(first.getWidth())
                    .putInt(first.getHeight())
                    .put((byte) sprite.levels().size());
            out.write(entry.array());
            for (NativeImage level : sprite.levels()) {
                ByteBuffer pixels = level.getPixelBytes();
                byte[] bytes = new byte[pixels.remaining()];
                pixels.get(bytes);
                out.write(bytes);
            }
        }
        out.flush();
    }
}
