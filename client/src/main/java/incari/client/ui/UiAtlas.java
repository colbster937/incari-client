package incari.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.render.TextureSetup;
import org.jspecify.annotations.Nullable;

/**
 * One texture for every small image the look paints itself: {@link RoundRects}' corners, {@link
 * Icons}, {@link Shapes}. The GUI batches its quads by texture, so with a texture for each corner
 * size and icon, every change between them cost a draw call; on one texture a screen's glass,
 * buttons and icons draw together. Images are painted once, as before, and copied into a free spot.
 *
 * <p>Pages of {@value #SIZE} pixels square, packed in shelves; a new page opens when one is full (a
 * change of GUI scale paints every size again). Each image has a transparent pixel around it, so
 * nearest sampling at its edge never reaches its neighbour.
 *
 * <p>Render thread.
 */
final class UiAtlas {

    /** A page's width and height in pixels. */
    static final int SIZE = 512;

    private static final float TEXEL = 1.0F / SIZE;

    private static final List<Page> PAGES = new ArrayList<>();

    private static @Nullable Region white;

    private UiAtlas() {}

    /** Where an image is: its page's texture, and its rectangle on it in pixels. */
    static final class Region {

        final TextureSetup texture;
        final int x;
        final int y;
        final int width;
        final int height;

        Region(TextureSetup texture, int x, int y, int width, int height) {
            this.texture = texture;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** The u coordinate of the image's pixel column {@code px} (its left edge). */
        float u(float px) {
            return (x + px) * TEXEL;
        }

        /** The v coordinate of the image's pixel row {@code py} (its top edge). */
        float v(float py) {
            return (y + py) * TEXEL;
        }
    }

    /**
     * Copies {@code image} into the atlas and closes it.
     *
     * @throws IllegalArgumentException if it's wider or taller than a page
     */
    static Region add(NativeImage image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            if (width + 2 > SIZE || height + 2 > SIZE) {
                throw new IllegalArgumentException("A " + width + "x" + height + " image doesn't fit the UI atlas");
            }
            Page page = PAGES.isEmpty() ? null : PAGES.getLast();
            long spot = page == null ? Shelves.FULL : page.shelves.place(width + 2, height + 2);
            if (page == null || spot == Shelves.FULL) {
                page = new Page(PAGES.size());
                PAGES.add(page);
                spot = page.shelves.place(width + 2, height + 2);
            }
            int x = Shelves.x(spot) + 1;
            int y = Shelves.y(spot) + 1;
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(page.texture, image, 0, 0, x, y);
            return new Region(page.setup, x, y, width, height);
        } finally {
            image.close();
        }
    }

    /** A small white square: plain fills drawn from the atlas, to batch with what's around them. */
    static Region white() {
        Region region = white;
        if (region == null) {
            NativeImage image = new NativeImage(3, 3, false);
            image.fillRect(0, 0, 3, 3, 0xFFFFFFFF);
            region = add(image);
            white = region;
        }
        return region;
    }

    /** One page: a texture, and the shelves filled on it so far. */
    private static final class Page {

        private final GpuTexture texture;
        private final TextureSetup setup;
        private final Shelves shelves = new Shelves(SIZE);

        Page(int index) {
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture(
                    () -> "Incari UI atlas " + index,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    SIZE,
                    SIZE,
                    1,
                    1);
            GpuTextureView view = device.createTextureView(texture);
            setup = TextureSetup.singleTexture(
                    view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            // A new texture's contents are undefined: the gaps between images must be clear.
            try (NativeImage clear = new NativeImage(SIZE, SIZE, true)) {
                device.createCommandEncoder().writeToTexture(texture, clear);
            }
        }
    }

    /**
     * Free space on a square page, filled in shelves: left to right along a shelf as tall as its
     * tallest image, then a new shelf below. Images of a few sizes come and stay, so nothing is
     * ever freed.
     */
    static final class Shelves {

        /** {@link #place}'s answer when there's no room. */
        static final long FULL = -1;

        private final int size;
        private int shelfY;
        private int shelfHeight;
        private int cursorX;

        Shelves(int size) {
            this.size = size;
        }

        /** A free {@code width} by {@code height} spot's corner ({@link #x}, {@link #y}), taken; or {@link #FULL}. */
        long place(int width, int height) {
            if (width > size || height > size) {
                return FULL;
            }
            int x = cursorX;
            int y = shelfY;
            int shelf = shelfHeight;
            if (x + width > size) {
                y += shelf;
                shelf = 0;
                x = 0;
            }
            if (y + height > size) {
                return FULL;
            }
            cursorX = x + width;
            shelfY = y;
            shelfHeight = Math.max(shelf, height);
            return (long) x << 32 | y;
        }

        static int x(long spot) {
            return (int) (spot >>> 32);
        }

        static int y(long spot) {
            return (int) spot;
        }
    }
}
