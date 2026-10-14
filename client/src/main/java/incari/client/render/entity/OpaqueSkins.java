package incari.client.render.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

/**
 * Translucent entity models that draw exactly as opaque ones, so the game can put them in its solid
 * phase and {@link ModelBatches} can draw them instanced (ADR 0027).
 *
 * <p>Players are drawn with {@code entity_translucent}: alpha blending, texels below 0.1 alpha
 * discarded, sorted with the other translucent models. Most skins have no texel between fully
 * transparent and fully opaque. Such a model, with an opaque tint, never blends: each of its pixels
 * is discarded or written whole, with depth. Drawn before every translucent model instead of among
 * them, it gives the same picture. A translucent model in front of it still blends over it, and one
 * behind it is hidden by depth, as it was when drawn first. Only fragments at exactly equal depth
 * could come out differently.
 *
 * <p>Which textures qualify is learned as their pixels are uploaded ({@link #uploaded}, hooks in
 * {@code DynamicTexture} for downloaded skins and {@code ReloadableTexture} for the game's own); a
 * texture never seen goes the game's way. Not with improved transparency (OIT), whose accumulation of
 * translucent models a solid draw doesn't reproduce.
 *
 * <p>Render thread only.
 */
public final class OpaqueSkins {

    /** Larger textures aren't scanned: entity textures are small, and a big one isn't a skin. */
    private static final int MAX_PIXELS = 512 * 512;

    /** Off on the desktop, which keeps the game's order; the page turns it on ({@code ?skins=game} leaves it off). */
    private static volatile boolean enabled;

    /** Whether each uploaded texture's alpha is all 0 or 255. */
    private static final Map<GpuTexture, Boolean> ALL_OR_NOTHING = new WeakHashMap<>();

    /** This frame's answers by render type (its texture can change between frames, on a reload). */
    private static final IdentityHashMap<RenderType, Boolean> ANSWERS = new IdentityHashMap<>();

    private static long answersFrame = -1;

    private OpaqueSkins() {}

    /** On in the page; the page's {@code ?skins=game} leaves every translucent model sorted with the rest. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Hook ({@code DynamicTexture.upload}, {@code ReloadableTexture.doLoad}): {@code image} is now
     * {@code texture}'s contents.
     */
    public static void uploaded(@Nullable GpuTexture texture, NativeImage image) {
        if (texture == null || !enabled) {
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (!image.format().hasAlpha() || (long) width * height > MAX_PIXELS) {
            ALL_OR_NOTHING.remove(texture);
            return;
        }
        boolean allOrNothing = true;
        for (int y = 0; y < height && allOrNothing; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = image.getPixel(x, y) >>> 24;
                if (alpha != 0 && alpha != 255) {
                    allOrNothing = false;
                    break;
                }
            }
        }
        ALL_OR_NOTHING.put(texture, allOrNothing);
    }

    /**
     * Hook ({@code SubmitNodeCollection.submitModel}), and {@link ModelBatches#take}: whether a model
     * submit of {@code renderType}, tinted {@code tintedColor}, draws as an opaque one would.
     */
    @SuppressWarnings("ReferenceEquality") // the game's pipeline constant
    public static boolean drawsAsSolid(RenderType renderType, int tintedColor) {
        if (!enabled
                || (tintedColor >>> 24) != 0xFF
                || renderType.pipeline() != RenderPipelines.ENTITY_TRANSLUCENT
                || Minecraft.getInstance().gameRenderer.useImprovedTransparency()) {
            return false;
        }
        if (answersFrame != ModelBatches.frame) {
            answersFrame = ModelBatches.frame;
            ANSWERS.clear();
        }
        Boolean known = ANSWERS.get(renderType);
        if (known != null) {
            return known;
        }
        boolean answer = false;
        for (PreparedRenderType.Texture texture : renderType.prepare().textures()) {
            if (texture.name().equals("Sampler0")) {
                answer = Boolean.TRUE.equals(
                        ALL_OR_NOTHING.get(texture.textureView().texture()));
            }
        }
        ANSWERS.put(renderType, answer);
        return answer;
    }
}
