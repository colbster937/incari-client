package incari.client.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import incari.client.ui.Look;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A screen's blurred backdrop kept between refreshes, a few a second, while the UI over it runs at
 * the full frame rate: the HUD editor's, in the Potato GUI preset. The editor blurs the world and
 * the game's HUD behind it in either preset, so its own elements stand out; on a Chromebook a
 * full-screen blur each frame, on top of the world, is what the Potato preset exists to avoid.
 *
 * <p>On a refresh the frame is drawn as usual, and right after the blur the screen is copied into a
 * texture. Until the next refresh the level is neither extracted nor drawn, nor the HUD (the level
 * isn't drawn that frame, as far as the GUI knows), and the copy takes the blur's place. The world
 * behind moves at {@link #REFRESHES_PER_SECOND}; under a blur that hardly shows. Those frames don't
 * age the game's pooled render targets either: it keeps one only three frames after its last use,
 * so each refresh would otherwise make the world's and the blur's full-screen targets again (on the
 * stand-in laptop, 15 ms of the refresh frame's CPU, and a missed display refresh).
 *
 * <p>Render thread only.
 */
public final class BackdropCache {

    /** How often the backdrop is drawn again while it's kept. */
    private static final int REFRESHES_PER_SECOND = 10;

    private static final long INTERVAL_MILLIS = 1000 / REFRESHES_PER_SECOND;

    /** Whether a screen asked for its backdrop to be kept. */
    private static boolean held;

    private static boolean reusing;
    private static int refreshes;
    private static boolean valid;
    private static long refreshedAt;
    private static @Nullable TextureTarget copy;

    private BackdropCache() {}

    /**
     * The HUD editor's, while it's shown: keep its backdrop in the Potato preset. Off frees the
     * copy.
     */
    public static void hold(boolean on) {
        held = on;
        valid = false;
        reusing = false;
        if (!on && copy != null) {
            copy.destroyBuffers();
            copy = null;
        }
    }

    /** How many times the backdrop has been drawn and kept: the smoke test's measure. */
    public static int refreshes() {
        return refreshes;
    }

    /** Whether frames draw the kept backdrop this frame: the panorama needn't be drawn either. */
    public static boolean reusing() {
        return reusing;
    }

    /**
     * Hook ({@code GameRenderer.extract}, before the level's extraction): a frame begins.
     *
     * @return true if it reuses the kept backdrop, so the level isn't extracted or drawn
     */
    public static boolean beginFrame(RenderTarget main) {
        reusing = false;
        if (!held || Look.normal()) {
            valid = false;
            return false;
        }
        TextureTarget kept = copy;
        if (kept == null || kept.width != main.width || kept.height != main.height) {
            valid = false;
        }
        reusing = valid && Util.getMillis() - refreshedAt < INTERVAL_MILLIS;
        return reusing;
    }

    /**
     * Hook ({@code GuiRenderer.draw}, in place of the blur): the kept backdrop copied onto the
     * screen, or the blur run and kept.
     *
     * @return false for the game to blur as usual: nothing is kept
     */
    public static boolean blur(RenderTarget main) {
        if (!held || Look.normal()) {
            return false;
        }
        GpuTexture screen = main.getColorTexture();
        if (screen == null) {
            return false;
        }
        if (reusing) {
            GpuTexture kept = copy == null ? null : copy.getColorTexture();
            if (kept != null) {
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .copyTextureToTexture(kept, screen, 0, 0, 0, 0, 0, main.width, main.height);
                return true;
            }
        }
        Minecraft.getInstance().gameRenderer.processBlurEffect();
        GpuTexture target = target(main).getColorTexture();
        if (target != null) {
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .copyTextureToTexture(screen, target, 0, 0, 0, 0, 0, main.width, main.height);
            valid = true;
            refreshedAt = Util.getMillis();
            refreshes++;
        }
        return true;
    }

    private static TextureTarget target(RenderTarget main) {
        TextureTarget kept = copy;
        if (kept == null || kept.width != main.width || kept.height != main.height) {
            if (kept != null) {
                kept.destroyBuffers();
            }
            kept = new TextureTarget("Incari backdrop", main.width, main.height, GpuFormat.RGBA8_UNORM, null);
            copy = kept;
        }
        return kept;
    }
}
