package incari.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import incari.client.tier.DeviceTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The largest texture atlas the device's tier allows (ADR 0025). The game sizes an atlas to
 * whatever its sprites need, up to the GPU's limit: a 64x resource pack makes a blocks atlas of 8192
 * by 8192 (256 MB with its mipmaps, on the GPU and again in memory while it loads), which a 4 GB
 * Chromebook can't hold. Here an atlas whose sprites don't fit the tier's size is loaded at half
 * resolution, and again, until they do; sprites keep their names and positions in their models, so
 * a pack looks as it would at a lower resolution. Vanilla's atlases (the largest is the blocks
 * atlas, 2048 by 2048) fit every tier, so nothing changes for them.
 *
 * <p>Two hooks: each sprite loaded passes through {@link #loaded}, which remembers how to load it
 * again at a lower resolution, and the stitch takes its sprites from {@link #fit} and its largest
 * size from {@link #maxSize}.
 */
public final class AtlasBudget {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasBudget.class);

    /** Below this a sprite isn't halved: a 16x pack's sprites stay as they are on every tier. */
    private static final int SMALLEST = 16;

    /** What each sprite was made from, until its atlas is stitched: sprites load on several threads. */
    private static final Map<SpriteContents, Source> SOURCES = new ConcurrentHashMap<>();

    private record Source(
            Identifier name,
            FrameSize frameSize,
            NativeImage image,
            Optional<AnimationMetadataSection> animation,
            List<MetadataSectionType.WithValue<?>> additionalMetadata,
            Optional<TextureMetadataSection> texture) {}

    private AtlasBudget() {}

    /** The largest atlas side at {@code tier}. */
    public static int maxSize(DeviceTier tier) {
        return switch (tier) {
            case POTATO -> 2048;
            case STANDARD -> 4096;
            case HIGH -> 16384;
        };
    }

    /** Hook: the largest atlas side, the device's limit {@code deviceMax} or the tier's if smaller. */
    public static int maxSize(int deviceMax) {
        return Math.min(deviceMax, maxSize(DeviceTier.current()));
    }

    /** Hook: the sprite the game's loader makes, remembered until its atlas is stitched. */
    public static SpriteContents loaded(
            Identifier name,
            FrameSize frameSize,
            NativeImage image,
            Optional<AnimationMetadataSection> animation,
            List<MetadataSectionType.WithValue<?>> additionalMetadata,
            Optional<TextureMetadataSection> texture) {
        SpriteContents sprite = new SpriteContents(name, frameSize, image, animation, additionalMetadata, texture);
        SOURCES.put(sprite, new Source(name, frameSize, image, animation, additionalMetadata, texture));
        return sprite;
    }

    /**
     * Hook: the sprites {@code atlas} is stitched from, at half resolution (or a quarter...) if at
     * full resolution they don't fit {@link #maxSize(int)}. Sprites halved are closed and replaced.
     */
    public static List<SpriteContents> fit(
            Identifier atlas, List<SpriteContents> sprites, int deviceMax, int maxMipLevels) {
        int size = maxSize(deviceMax);
        List<SpriteContents> current = sprites;
        try {
            int factor = 1;
            while (!fits(current, size, maxMipLevels)) {
                List<SpriteContents> halved = halve(current);
                if (halved == null) {
                    break; // Nothing left to halve: the game's stitch reports it.
                }
                current = halved;
                factor *= 2;
            }
            if (factor > 1) {
                LOGGER.info(
                        "{}: loaded at 1/{} resolution to fit {}x{} ({} tier)",
                        atlas,
                        factor,
                        size,
                        size,
                        DeviceTier.current());
            }
            return current;
        } finally {
            sprites.forEach(SOURCES::remove);
            current.forEach(SOURCES::remove);
        }
    }

    /** Whether {@code sprites} stitch into {@code size} by {@code size}, padded as the game will pad them. */
    private static boolean fits(List<SpriteContents> sprites, int size, int maxMipLevels) {
        int mipLevel = mipLevel(sprites, maxMipLevels);
        int padding = 1 << mipLevel << Mth.clamp(anisotropyBit() - 1, 0, 4);
        long area = 0;
        for (SpriteContents sprite : sprites) {
            area += (long) (sprite.width() + 2 * padding) * (sprite.height() + 2 * padding);
        }
        if (area > (long) size * size) {
            return false;
        }
        if (area * 4 <= (long) size * size) {
            return true; // Far from full: no need to try.
        }
        Stitcher<SpriteContents> trial = new Stitcher<>(size, size, mipLevel, anisotropyBit());
        sprites.forEach(trial::registerSprite);
        try {
            trial.stitch();
            return true;
        } catch (StitcherException e) {
            return false;
        }
    }

    /** The mip levels the game will give these sprites: as many as the smallest power of two in them allows. */
    private static int mipLevel(List<SpriteContents> sprites, int maxMipLevels) {
        int lowestBit = 1 << maxMipLevels;
        int smallest = Integer.MAX_VALUE;
        for (SpriteContents sprite : sprites) {
            smallest = Math.min(smallest, Math.min(sprite.width(), sprite.height()));
            lowestBit = Math.min(
                    lowestBit, Math.min(Integer.lowestOneBit(sprite.width()), Integer.lowestOneBit(sprite.height())));
        }
        return Math.min(maxMipLevels, Mth.log2(Math.min(smallest, lowestBit)));
    }

    private static int anisotropyBit() {
        var options = Minecraft.getInstance().options;
        return options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
                ? options.maxAnisotropyBit().get()
                : 0;
    }

    /** Every sprite bigger than {@link #SMALLEST} at half resolution; null if there's none. */
    private static @Nullable List<SpriteContents> halve(List<SpriteContents> sprites) {
        List<SpriteContents> out = new ArrayList<>(sprites.size());
        boolean any = false;
        for (SpriteContents sprite : sprites) {
            Source source = SOURCES.get(sprite);
            if (source == null
                    || source.frameSize.width() <= SMALLEST
                    || source.frameSize.height() <= SMALLEST
                    || source.frameSize.width() % 2 != 0
                    || source.frameSize.height() % 2 != 0) {
                out.add(sprite);
                continue;
            }
            Source smaller = new Source(
                    source.name,
                    new FrameSize(source.frameSize.width() / 2, source.frameSize.height() / 2),
                    halfImage(source.image),
                    source.animation,
                    source.additionalMetadata,
                    source.texture);
            SpriteContents replacement = new SpriteContents(
                    smaller.name,
                    smaller.frameSize,
                    smaller.image,
                    smaller.animation,
                    smaller.additionalMetadata,
                    smaller.texture);
            SOURCES.remove(sprite);
            SOURCES.put(replacement, smaller);
            sprite.close();
            out.add(replacement);
            any = true;
        }
        return any ? out : null;
    }

    /** {@code image} at half its size, each pixel the mean of the four it replaces (alpha weighted). */
    static NativeImage halfImage(NativeImage image) {
        int width = image.getWidth() / 2;
        int height = image.getHeight() / 2;
        NativeImage half = new NativeImage(image.format(), width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                half.setPixel(
                        x,
                        y,
                        mean(
                                image.getPixel(2 * x, 2 * y),
                                image.getPixel(2 * x + 1, 2 * y),
                                image.getPixel(2 * x, 2 * y + 1),
                                image.getPixel(2 * x + 1, 2 * y + 1)));
            }
        }
        return half;
    }

    /**
     * The mean of four ARGB pixels, colour weighted by alpha so a transparent pixel's colour doesn't
     * bleed into its neighbours' (the dark fringe a plain mean gives cut-out leaves).
     */
    static int mean(int a, int b, int c, int d) {
        int alpha = (a >>> 24) + (b >>> 24) + (c >>> 24) + (d >>> 24);
        if (alpha == 0) {
            return 0;
        }
        int red = weighted(a, b, c, d, 16, alpha);
        int green = weighted(a, b, c, d, 8, alpha);
        int blue = weighted(a, b, c, d, 0, alpha);
        return (alpha / 4) << 24 | red << 16 | green << 8 | blue;
    }

    private static int weighted(int a, int b, int c, int d, int shift, int alpha) {
        int sum = ((a >>> shift) & 0xFF) * (a >>> 24)
                + ((b >>> shift) & 0xFF) * (b >>> 24)
                + ((c >>> shift) & 0xFF) * (c >>> 24)
                + ((d >>> shift) & 0xFF) * (d >>> 24);
        return (sum + alpha / 2) / alpha;
    }
}
