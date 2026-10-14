package incari.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import incari.client.render.mesher.MeshTableCache;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Animates only the block sprites something draws (ADR 0030), Sodium's idea. Each tick the game
 * draws the next frame of every animated sprite of the blocks atlas that changed, into each of the
 * atlas's mip levels: in 26.3, 54 sprites (water, lava, fire, portals, sculk, command blocks...)
 * and about 40 draws a tick per level, five levels. In the browser, where every draw is a WebGL
 * call, that was most of a frame's draws outside terrain, for sprites mostly nowhere in sight.
 *
 * <p>A sprite counts as drawn when a visible terrain section uses it (Incari's mesher says which
 * sprites each section uses) or when anything else reads its texture coordinates or draws a baked
 * quad of it (items, particles, entity fire, the GUI), this frame or the last. The others keep
 * ticking, so their animation stays in step with the game's, but their frames aren't drawn into the
 * atlas; one left behind is drawn as it is now the moment it's needed again. For terrain that is
 * before the terrain is drawn ({@link #terrainFrame}); for anything else, at the next tick or
 * frame, so the first frame something else shows a sprite after a long while can show the frame it
 * last had.
 *
 * <p>When the terrain's sprites aren't known (sections the game's compiler meshed, a
 * resource reload under way, no world drawn) every sprite is drawn, as the game does. Render thread
 * only; the desktop's compile threads read sprites too, but there the terrain's sprites are never
 * known.
 */
// The game names the blocks atlas by its deprecated LOCATION_BLOCKS everywhere, as here.
@SuppressWarnings("deprecation")
public final class AnimatedSprites {

    /** How long a frame of terrain sprites stays good for, when no terrain is drawn after it. */
    private static final long TERRAIN_GOOD_FOR_MILLIS = 250;

    private static volatile boolean enabled = true;
    /** Read by the threads that build the mesher's tables; changed on the render thread. */
    private static final AtomicInteger GENERATION = new AtomicInteger(1);

    private static List<SpriteContents.AnimationState> states = List.of();
    private static GpuTextureView[] mipViews = new GpuTextureView[0];
    private static @Nullable TextureAtlas atlas;
    /** Per sprite: the atlas holds an older frame than its animation is at. */
    private static boolean[] stale = new boolean[0];

    private static boolean[] draw = new boolean[0];
    private static long[] terrain = new long[0];
    private static long terrainAt = Long.MIN_VALUE;
    private static boolean terrainKnown;
    private static long[] usedThisFrame = new long[0];
    private static long[] usedLastFrame = new long[0];

    private AnimatedSprites() {}

    /** The page's {@code ?animations=all}: every animated sprite drawn, as the game does. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * The ids' generation: a new one each time the blocks atlas is built. A section meshed with
     * another generation's ids says nothing about this one's.
     */
    public static int generation() {
        return GENERATION.get();
    }

    /** The id a block sprite's animation has, or -1 if it isn't an animated block sprite. */
    public static int id(TextureAtlasSprite sprite) {
        return sprite.incariAnimatedId;
    }

    /**
     * Hook ({@code TextureAtlas}): an atlas was built with these sprites and animations, the
     * animations in the order of the sprites that are animated. The blocks atlas's become ids.
     */
    public static void atlasBuilt(
            TextureAtlas built,
            List<TextureAtlasSprite> sprites,
            List<SpriteContents.AnimationState> animations,
            GpuTextureView[] views) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(built.location())) {
            return;
        }
        int id = 0;
        for (TextureAtlasSprite sprite : sprites) {
            sprite.incariAnimatedId = sprite.isAnimated() ? id++ : -1;
        }
        boolean usable = id == animations.size();
        if (!usable) {
            // Not one animation per animated sprite: leave the atlas to the game.
            for (TextureAtlasSprite sprite : sprites) {
                sprite.incariAnimatedId = -1;
            }
        }
        // The kept mesher tables' key: the quads the tables hold carry these coordinates and ids.
        MeshTableCache.atlasBuilt(sprites);
        if (!usable) {
            atlasCleared(built);
            return;
        }
        GENERATION.incrementAndGet();
        atlas = built;
        states = animations;
        mipViews = views;
        stale = new boolean[id];
        draw = new boolean[id];
        int words = (id + 63) >> 6;
        terrain = new long[words];
        usedThisFrame = new long[words];
        usedLastFrame = new long[words];
        terrainKnown = false;
    }

    /** Hook ({@code TextureAtlas.clearTextureData}): the atlas's animations are closed. */
    @SuppressWarnings("ReferenceEquality") // the atlas object itself
    public static void atlasCleared(TextureAtlas cleared) {
        if (atlas == cleared) {
            atlas = null;
            states = List.of();
            mipViews = new GpuTextureView[0];
            GENERATION.incrementAndGet();
        }
    }

    /** Hook ({@code TextureAtlasSprite}'s coordinate getters): sprite {@code id} is being drawn. */
    public static void used(int id) {
        long[] used = usedThisFrame;
        if (id >> 6 < used.length) {
            used[id >> 6] |= 1L << id;
        }
    }

    /** A baked quad of {@code quad}'s sprite is being drawn (its coordinates were baked in). */
    public static void used(BakedQuad quad) {
        // Never null in the game; tests make quads without sprites.
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        if (sprite != null && sprite.incariAnimatedId >= 0) {
            used(sprite.incariAnimatedId);
        }
    }

    /**
     * Hook ({@code TextureAtlas.cycleAnimationFrames}, after every animation ticked): draws the
     * animations that changed and are drawn somewhere; false for an atlas left to the game.
     */
    @SuppressWarnings("ReferenceEquality") // the atlas and its animation list themselves
    public static boolean upload(TextureAtlas ticked, List<SpriteContents.AnimationState> animations) {
        if (!enabled || ticked != atlas || animations != states) {
            return false;
        }
        boolean known = terrainKnown && Util.getMillis() - terrainAt < TERRAIN_GOOD_FOR_MILLIS;
        boolean any = false;
        for (int id = 0; id < draw.length; id++) {
            boolean needed = animations.get(id).needsToDraw() || stale[id];
            boolean seen = !known || isSet(terrain, id) || isSet(usedThisFrame, id) || isSet(usedLastFrame, id);
            draw[id] = needed && seen;
            stale[id] = needed && !seen;
            any |= draw[id];
        }
        if (any) {
            drawMarked();
        }
        return true;
    }

    /**
     * Hook ({@code TerrainDrawLists}, once a frame before terrain is drawn): the animated sprites
     * the frame's visible sections use. Any of them left behind is drawn now.
     *
     * @param complete false if some visible section doesn't say which sprites it uses
     */
    public static void terrainFrame(long[] seen, boolean complete) {
        if (atlas == null) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < terrain.length; i++) {
            terrain[i] = i < seen.length ? seen[i] : 0L;
        }
        terrainKnown = complete;
        terrainAt = Util.getMillis();
        for (int id = 0; id < draw.length; id++) {
            boolean seenNow = !complete || isSet(terrain, id) || isSet(usedThisFrame, id) || isSet(usedLastFrame, id);
            draw[id] = stale[id] && seenNow;
            if (draw[id]) {
                stale[id] = false;
                any = true;
            }
        }
        if (any) {
            drawMarked();
        }
        long[] last = usedLastFrame;
        usedLastFrame = usedThisFrame;
        Arrays.fill(last, 0L);
        usedThisFrame = last;
    }

    /** Draws each marked animation's current frame into every mip level, as the game does. */
    private static void drawMarked() {
        for (int id = 0; id < draw.length; id++) {
            SpriteContents.AnimationState state = states.get(id);
            if (draw[id] && !state.needsToDraw()) {
                // Left behind on a frame it has since stopped changing: draw that frame anyway.
                state.incariRedraw();
            }
        }
        String label = "Animate " + TextureAtlas.LOCATION_BLOCKS;
        for (int level = 0; level < mipViews.length; level++) {
            try (RenderPass pass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> label, mipViews[level], Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                for (int id = 0; id < draw.length; id++) {
                    if (draw[id]) {
                        SpriteContents.AnimationState state = states.get(id);
                        state.drawToAtlas(pass, state.getDrawUbo(level));
                    }
                }
            }
        }
    }

    private static boolean isSet(long[] bits, int id) {
        return id >> 6 < bits.length && (bits[id >> 6] & 1L << id) != 0;
    }
}
