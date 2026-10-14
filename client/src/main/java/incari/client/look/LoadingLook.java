package incari.client.look;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import incari.client.ui.Logo;
import incari.client.ui.MenuBackdrop;
import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The loading screen in the client's look: the menus' photo ({@link MenuBackdrop}), Incari's mark
 * in a soft glow, and a loading bar drawn as the mark's comet, an ice-blue trail with a bright head.
 * The game's {@code LoadingOverlay} stays the overlay and keeps its timing (when it fades in and
 * out); two hooks hand its backdrop and its logo and bar over to this.
 *
 * <p>It draws during the first resource reload, so it uses nothing a reload loads: no text (the
 * fonts aren't there yet), only the photo and the mark, read from the class path, and the look's
 * painted shapes. The same screen starts the title, so the fade out into it changes nothing behind.
 *
 * <p>Render thread.
 */
public final class LoadingLook {

    /** A light dim over the photo so the mark stands out. */
    private static final int DIM = 0x330C131B;
    /** The backdrop when the player asked for a dark loading screen (Monochrome Logo). */
    private static final int DARK = 0xFF0C131B;

    private static final int BAR_HEIGHT = 4;
    /** How quickly the bar catches up with the reload's progress: about two thirds in this time. */
    private static final float CATCH_UP_MILLIS = 180.0F;
    /** One pulse of the bar's head, in milliseconds. */
    private static final float PULSE_MILLIS = 1400.0F;

    /** The overlay the bar's progress belongs to: a new one starts it at 0. */
    private static @Nullable Object overlay;

    private static final Identifier GLOW = Identifier.fromNamespaceAndPath("incari", "loading_glow");
    private static final int GLOW_PIXELS = 64;

    private static boolean glowMade;
    private static float shown;
    private static long shownAt;

    private LoadingLook() {}

    /** {@code LoadingOverlay}'s backdrop, over the whole screen at {@code alpha} (0 to 255). */
    public static void background(GuiGraphicsExtractor graphics, int width, int height, int alpha) {
        float fade = Math.max(0, Math.min(255, alpha)) / 255.0F;
        if (Minecraft.getInstance().options.darkMojangStudiosBackground().get()) {
            graphics.fill(0, 0, width, height, ARGB.multiplyAlpha(DARK, fade));
            return;
        }
        MenuBackdrop.extract(graphics, width, height, fade);
        graphics.fill(0, 0, width, height, ARGB.multiplyAlpha(DIM, fade));
    }

    /**
     * {@code LoadingOverlay}'s logo and bar: the mark at {@code markAlpha} (0 to 1) and the bar
     * toward {@code progress} (0 to 1) at {@code barAlpha}, for {@code owner}, the overlay. Returns
     * true: the game's logo and bar are left out.
     */
    @SuppressWarnings("ReferenceEquality") // the overlay object, not an equal one
    public static boolean content(
            Object owner, GuiGraphicsExtractor graphics, float markAlpha, float progress, float barAlpha) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        long now = Util.getMillis();
        if (owner != overlay) {
            overlay = owner;
            shown = 0.0F;
            shownAt = now;
        }
        // By time, not by frame: the game's per-frame smoothing lags far behind at a low frame rate.
        float step = 1.0F - (float) Math.exp(-(now - shownAt) / CATCH_UP_MILLIS);
        shown = Math.max(shown, shown + (Math.max(0, Math.min(1, progress)) - shown) * step);
        shownAt = now;

        int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        // A quarter of the screen's smaller side, at most the mark's 256 source pixels.
        int size = Math.max(24, Math.min(Math.min(width, height) / 4, 256 / scale));
        int gap = Math.max(14, size / 4);
        int top = (height - size - gap - BAR_HEIGHT) / 2;
        int markX = (width - size) / 2;
        if (markAlpha > 0.0F) {
            // A soft round glow behind the mark, half again its size.
            RoundRects.pixels(graphics);
            glow(
                    graphics,
                    (markX + size / 2) * scale,
                    (top + size / 2) * scale,
                    size * scale * 3 / 2,
                    ARGB.multiplyAlpha(Theme.ACCENT & 0x8CFFFFFF, markAlpha));
            graphics.pose().popMatrix();
            Logo.draw(graphics, markX, top, size, markAlpha);
        }
        if (barAlpha > 0.0F) {
            int barWidth = Math.max(size, Math.min(240, width * 2 / 5));
            bar(graphics, (width - barWidth) / 2, top + size + gap, barWidth, barAlpha, now);
        }
        return true;
    }

    /** The comet: a faint track, the trail filled to {@link #shown} in a glow, its head a bright dot. */
    private static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, float alpha, long now) {
        int radius = BAR_HEIGHT / 2;
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                BAR_HEIGHT,
                radius,
                ARGB.multiplyAlpha(0x2EFFFFFF, alpha),
                ARGB.multiplyAlpha(0x1FFFFFFF, alpha));
        int filled = Math.max(BAR_HEIGHT, Math.round(width * shown));
        RoundRects.box(
                graphics,
                x,
                y,
                filled,
                BAR_HEIGHT,
                radius,
                ARGB.multiplyAlpha(Theme.ACCENT, alpha),
                0,
                6,
                0,
                ARGB.multiplyAlpha(Theme.ACCENT & 0x66FFFFFF, alpha));

        // The head pulses gently, so a long wait on one step still looks alive.
        float pulse = 0.5F + 0.5F * (float) Math.sin(now % (long) PULSE_MILLIS / PULSE_MILLIS * 2 * Math.PI);
        int scale = RoundRects.pixels(graphics);
        int centerX = (x + filled) * scale - scale;
        int centerY = (y * 2 + BAR_HEIGHT) * scale / 2;
        int halo = Math.round(BAR_HEIGHT * scale * (4.0F + pulse));
        int head = BAR_HEIGHT * scale * 4;
        glow(graphics, centerX, centerY, halo, ARGB.multiplyAlpha(Theme.ACCENT & 0xE6FFFFFF, alpha));
        glow(graphics, centerX, centerY, head, ARGB.white(alpha));
        graphics.pose().popMatrix();
    }

    /**
     * A soft round glow {@code diameter} screen pixels across, centred on ({@code centerX}, {@code
     * centerY}) in screen pixels: call between {@link RoundRects#pixels} and its {@code popMatrix}.
     * Its own smoothly sampled texture: the look's dots are small and sampled to the pixel, and
     * blow up blocky at this size.
     */
    private static void glow(GuiGraphicsExtractor graphics, int centerX, int centerY, int diameter, int argb) {
        if (!glowMade) {
            glowMade = true;
            NativeImage image = new NativeImage(GLOW_PIXELS, GLOW_PIXELS, false);
            for (int py = 0; py < GLOW_PIXELS; py++) {
                for (int px = 0; px < GLOW_PIXELS; px++) {
                    float dx = (px + 0.5F) / GLOW_PIXELS * 2 - 1;
                    float dy = (py + 0.5F) / GLOW_PIXELS * 2 - 1;
                    float t = Math.max(0, 1 - (float) Math.sqrt(dx * dx + dy * dy));
                    image.setPixel(px, py, Math.round(t * t * 255.0F) << 24 | 0xFFFFFF);
                }
            }
            Minecraft.getInstance().getTextureManager().register(GLOW, new Smooth(image));
        }
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                GLOW,
                centerX - diameter / 2,
                centerY - diameter / 2,
                0,
                0,
                diameter,
                diameter,
                GLOW_PIXELS,
                GLOW_PIXELS,
                GLOW_PIXELS,
                GLOW_PIXELS,
                argb);
    }

    /** A painted texture sampled smoothly and clamped at its edges, its pixels freed once uploaded. */
    private static final class Smooth extends DynamicTexture {

        Smooth(NativeImage image) {
            super(GLOW::toString, image);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            image.close();
        }
    }
}
