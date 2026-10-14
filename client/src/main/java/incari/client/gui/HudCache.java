package incari.client.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import incari.client.tier.DeviceTier;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The HUD drawn into a texture a few dozen times a second and reused in between (ADR 0030),
 * Exordium's idea. With no screen open, the GUI is the HUD: the hotbar and its items, hearts, food
 * and experience, the chat, the sidebar, boss bars, toasts, F3's text. The game extracts, lays out,
 * uploads and draws all of it every frame, though it changes a few times a second at most.
 *
 * <p>The HUD's first stratum stays live: the camera overlays (vignette, pumpkin, spyglass, powder
 * snow, the portal) and the crosshair, whose blends depend on the world behind them. Everything the
 * HUD puts above it goes, on a refresh, into a texture of its own instead of the screen, the GUI's
 * usual blending leaving its colors premultiplied by their coverage; every frame that texture is
 * blended over the screen. Between refreshes nothing above the first stratum is extracted at all.
 * Only while frames come faster than refreshes: at a lower frame rate every frame would refresh,
 * and the texture would only add a pass, so the HUD is drawn the game's way.
 * So the HUD lags the game by up to one refresh interval (50 ms at Standard, 66 at Potato), and its
 * colors can differ from the game's by the rounding of one 8-bit step. High, the desktop, and
 * {@code ?hud=live} draw it every frame, as the game does.
 *
 * <p>The split rides on the GUI's own one place to divide a frame's strata (where a screen blurs
 * what's behind it): with no screen open nothing else uses it. Render thread only.
 */
public final class HudCache {

    private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("incari", "pipeline/hud_composite"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            // The game's screen quad and plain blit: the stages the page already has translated.
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, 15))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private static final Vector4f CLEAR = new Vector4f(0.0F);

    private static volatile boolean enabled = true;

    private static boolean candidate;
    private static boolean reuse;
    private static boolean split;
    private static boolean cleared;
    /** Whether the last refresh drew anything above the split: an empty HUD isn't blended. */
    private static boolean drawn;

    private static boolean valid;
    private static long refreshedAt;
    private static long lastFrameAt;
    /** The frames' recent length, smoothed: the texture only pays off when frames come faster. */
    private static float frameMillis;

    private static @Nullable TextureTarget target;

    private HudCache() {}

    /** The page's {@code ?hud=live}: the HUD drawn every frame, as the game does. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** How long a drawn HUD is reused for; 0 for every frame. */
    private static long intervalMillis() {
        return switch (DeviceTier.current()) {
            case POTATO -> 66;
            case STANDARD -> 50;
            case HIGH -> 0;
        };
    }

    /**
     * Hook ({@code Gui.extractRenderState}, first): a frame's GUI is about to be extracted.
     *
     * @param hudOnly whether it's the level's HUD alone: a level drawn, no screen or overlay open
     */
    public static void beginFrame(boolean hudOnly) {
        split = false;
        long interval = intervalMillis();
        long now = Util.getMillis();
        if (lastFrameAt != 0) {
            frameMillis += (Math.min(now - lastFrameAt, 1000) - frameMillis) * 0.1F;
        }
        lastFrameAt = now;
        // Frames as long as a refresh interval would refresh every frame: drawing into the texture and
        // blending it back would only add a pass. Then the HUD is drawn the game's way.
        candidate = enabled && hudOnly && interval > 0 && frameMillis < interval * 0.8F;
        if (!candidate) {
            valid = false;
            reuse = false;
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        TextureTarget kept = target;
        if (kept == null || kept.width != main.width || kept.height != main.height) {
            valid = false;
        }
        reuse = valid && now - refreshedAt < interval;
    }

    /**
     * Hook ({@code Hud.extractRenderState}, after the crosshair's stratum): divides the frame there.
     *
     * @return true to skip the rest of the HUD, which the last drawing still holds
     */
    public static boolean split(GuiRenderState state) {
        if (!candidate) {
            return false;
        }
        state.blurBeforeThisStratum();
        split = true;
        if (!reuse) {
            refreshedAt = Util.getMillis();
            cleared = false;
            drawn = false;
            valid = true;
        }
        return reuse;
    }

    /** Hook ({@code Gui.extractRenderState}): skip the rest of the GUI, the last drawing has it. */
    public static boolean reusing() {
        return split && reuse;
    }

    /**
     * Hook ({@code GuiRenderer.draw}): whether the strata above the split go into the HUD's texture
     * ({@link #target}) rather than after a blur.
     */
    public static boolean divided() {
        return split;
    }

    /** The HUD's texture, cleared the first time it's asked for on a refresh. */
    public static RenderTarget target() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        TextureTarget kept = target;
        if (kept == null || kept.width != main.width || kept.height != main.height) {
            if (kept != null) {
                kept.destroyBuffers();
            }
            kept = new TextureTarget("Incari HUD", main.width, main.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            target = kept;
            cleared = false;
        }
        GpuTexture color = kept.getColorTexture();
        GpuTexture depth = kept.getDepthTexture();
        drawn = true;
        if (!cleared && color != null && depth != null) {
            cleared = true;
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, CLEAR, depth, 0.0);
        }
        return kept;
    }

    /** Hook ({@code GuiRenderer.render}, after its draws): blends the HUD's texture over the screen. */
    public static void composite() {
        // Nothing above the split (a spectator's HUD, say): nothing to blend.
        if (!split || !drawn) {
            return;
        }
        TextureTarget kept = target;
        GpuTextureView hud = kept == null ? null : kept.getColorTextureView();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView screen = main.getColorTextureView();
        if (hud == null || screen == null) {
            return;
        }
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Incari HUD", screen, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("InSampler", hud, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }
}
