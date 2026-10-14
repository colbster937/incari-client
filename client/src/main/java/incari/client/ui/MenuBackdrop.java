package incari.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.slf4j.Logger;

/**
 * What menus show behind them out of a world, in place of the game's panorama: an Alpine photo,
 * already blurred and darkened by {@code tools/backdrop}. The loading screen, the title screen,
 * every screen's backdrop ({@link FrostSkin}) and the game's own panorama draws ({@code
 * Screen.extractPanorama}: the connecting and loading screens) all draw it, so moving between them
 * never changes the picture. It covers the screen whatever its shape, cropping the photo's sides
 * on narrow screens and mostly its bottom (the cloud) on wide ones, so the peaks stay.
 *
 * <p>Read from the class path, not through the resource system: the loading screen draws it during
 * the first resource reload, before any pack's textures are loaded, and a reload leaves it alone.
 *
 * <p>One textured quad a frame, where the panorama renders a cube map: cheaper on the floor device.
 * It's already blurred, so the Normal preset's blur pass isn't needed over it. Render thread.
 */
public final class MenuBackdrop {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SOURCE = "/incari-pack/assets/incari/textures/gui/title_backdrop.png";
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("incari", "menu_backdrop");
    /** Where the crop is centred, as a fraction of the photo's height: on the peak line. */
    private static final float FOCUS_Y = 0.35F;
    /** Drawn instead if the photo couldn't be read: the look's darkest blue. */
    private static final int FALLBACK = 0xFF0C131B;
    /**
     * The blit's texture coordinates are integers over the texture's size; giving it this size
     * instead keeps the crop's fractions exact enough.
     */
    private static final int UV_UNITS = 1 << 16;

    /** 0 until the photo is read, then its width over its height; -1 if it couldn't be. */
    private static float aspect;

    private MenuBackdrop() {}

    /** Draws the photo over the whole screen, {@code width} by {@code height} GUI units. */
    public static void extract(GuiGraphicsExtractor graphics, int width, int height) {
        extract(graphics, width, height, 1.0F);
    }

    /** {@link #extract(GuiGraphicsExtractor, int, int)} at {@code alpha} (0 to 1). */
    public static void extract(GuiGraphicsExtractor graphics, int width, int height, float alpha) {
        if (width <= 0 || height <= 0 || alpha <= 0.0F) {
            return;
        }
        float photo = aspect();
        int color = ARGB.white(Math.min(1.0F, alpha));
        if (photo <= 0.0F) {
            graphics.fill(0, 0, width, height, ARGB.multiply(FALLBACK, color));
            return;
        }
        float screen = (float) width / height;
        float u0 = 0.0F;
        float v0 = 0.0F;
        float uSpan = 1.0F;
        float vSpan = 1.0F;
        if (screen > photo) {
            vSpan = photo / screen;
            v0 = Math.max(0.0F, Math.min(1.0F - vSpan, FOCUS_Y - vSpan / 2));
        } else {
            uSpan = screen / photo;
            u0 = (1.0F - uSpan) / 2;
        }
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                TEXTURE,
                0,
                0,
                u0 * UV_UNITS,
                v0 * UV_UNITS,
                width,
                height,
                Math.round(uSpan * UV_UNITS),
                Math.round(vSpan * UV_UNITS),
                UV_UNITS,
                UV_UNITS,
                color);
    }

    /** The photo's aspect, reading it and making its texture the first time. */
    private static float aspect() {
        if (aspect == 0.0F) {
            aspect = -1.0F;
            try (InputStream in = MenuBackdrop.class.getResourceAsStream(SOURCE)) {
                if (in == null) {
                    LOGGER.warn("Incari's pack has no {}", SOURCE);
                } else {
                    NativeImage image = NativeImage.read(in);
                    float read = (float) image.getWidth() / image.getHeight();
                    Minecraft.getInstance().getTextureManager().register(TEXTURE, new Photo(image));
                    aspect = read;
                }
            } catch (IOException e) {
                LOGGER.warn("Couldn't read the menus' photo: {}", e.toString());
            }
        }
        return aspect;
    }

    /**
     * The photo's texture, sampled smoothly (it's stretched past its size on most screens) and
     * clamped (the default repeat would bleed the opposite edge in along the border).
     */
    private static final class Photo extends DynamicTexture {

        Photo(NativeImage image) {
            super(TEXTURE::toString, image);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            // Uploaded once and never changed: its 2.4 MB of pixels needn't stay in memory too.
            image.close();
        }
    }
}
