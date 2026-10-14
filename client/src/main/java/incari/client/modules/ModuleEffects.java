package incari.client.modules;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import incari.framework.ModuleSystem;
import incari.framework.event.Events;
import incari.framework.game.Effect;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The personal effects modules hold (ADR 0061): the block outline's color, width and fill (Block
 * Outline), the hurt tint (Hit Color), the enchantment glint's color (Enchant Glint), and particles
 * on what the player hit and where it died (Hit &amp; Kill Effects). All on this client only.
 *
 * <p>Render and game thread (one thread in Incari's builds). The outline's hooks run each frame:
 * while no module holds the outline they're a field read.
 */
public final class ModuleEffects {

    /** How long after a hit a death counts as the player's kill. */
    private static final int KILL_TICKS = 60;

    /**
     * The game's own hurt tint ({@code OverlayTexture}): red, with alpha (70%) the share of the
     * entity's color kept ({@code entity.fsh} mixes by it).
     */
    private static final int GAME_HIT_COLOR = 0xB2FF0000;

    private static int outlineColor;
    private static float outlineWidth;
    private static int outlineFill;
    private static final Fill FILL = new Fill();

    private static float brightness;

    /** The hurt tint painted while a module holds one; {@link #hitColorHeld} false for the game's. */
    private static int hitColor = GAME_HIT_COLOR;

    private static boolean hitColorHeld;
    private static @Nullable DynamicTexture overlay;

    private static int glintColor = -1;

    private static @Nullable Entity target;
    private static int targetTick;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastWidth = 0.6F;
    private static float lastHeight = 1.8F;
    private static boolean hadTarget;

    private ModuleEffects() {}

    // ---------------------------------------------------------------------------------------------
    // Fullbright
    // ---------------------------------------------------------------------------------------------

    /** {@code Game.overrideBrightness}'s. */
    static void brightness(float level) {
        brightness = Math.clamp(level, 0, 1);
    }

    /**
     * Hook ({@code LightmapRenderStateExtractor.extract}): night vision's strength in the lightmap,
     * Fullbright's if that's more. The lightmap is redone once a tick, so a change shows by the next.
     */
    public static float nightVision(float intensity) {
        return Math.max(intensity, brightness);
    }

    // ---------------------------------------------------------------------------------------------
    // Block Outline
    // ---------------------------------------------------------------------------------------------

    /** {@code Game.overrideBlockOutline}'s. */
    static void outline(int argb, float width, int fillArgb) {
        outlineColor = argb;
        outlineWidth = width;
        outlineFill = fillArgb;
    }

    /**
     * Hook ({@code LevelRenderer.submitBlockOutline}): the outline's color, and its fill submitted
     * under it. The game's own color while no module holds it, or with high contrast on.
     */
    public static int outlineColor(
            int color, boolean highContrast, PoseStack poseStack, SubmitNodeCollector collector, VoxelShape shape) {
        if (outlineColor == 0 || highContrast) {
            return color;
        }
        if (outlineFill != 0) {
            FILL.shape = shape;
            FILL.color = outlineFill;
            collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox(), FILL);
        }
        return outlineColor;
    }

    /** Hook ({@code LevelRenderer.submitBlockOutline}): the outline's width, scaled as the game scales its own. */
    public static float outlineWidth(float width) {
        if (outlineColor == 0 || outlineWidth <= 0) {
            return width;
        }
        // The game's width is 2.5 at 1080 lines, more on taller windows: the module's scales alike.
        return outlineWidth * Math.max(1.0F, width / 2.5F);
    }

    /** The block's faces, a hair outside it so they don't fight its own. One at a time: one outline a frame. */
    private static final class Fill implements SubmitNodeCollector.CustomGeometryRenderer {
        private static final double OUT = 0.002;

        @Nullable
        VoxelShape shape;

        int color;

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            VoxelShape current = shape;
            if (current == null) {
                return;
            }
            int argb = color;
            current.forAllBoxes((x0, y0, z0, x1, y1, z1) -> box(
                    pose,
                    buffer,
                    (float) (x0 - OUT),
                    (float) (y0 - OUT),
                    (float) (z0 - OUT),
                    (float) (x1 + OUT),
                    (float) (y1 + OUT),
                    (float) (z1 + OUT),
                    argb));
        }

        private static void box(
                PoseStack.Pose pose,
                VertexConsumer buffer,
                float x0,
                float y0,
                float z0,
                float x1,
                float y1,
                float z1,
                int argb) {
            quad(pose, buffer, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(pose, buffer, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(pose, buffer, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(pose, buffer, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(pose, buffer, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(pose, buffer, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }

        @SuppressWarnings("TooManyParameters") // a quad is four corners
        private static void quad(
                PoseStack.Pose pose,
                VertexConsumer buffer,
                int argb,
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float cx,
                float cy,
                float cz,
                float dx,
                float dy,
                float dz) {
            buffer.addVertex(pose, ax, ay, az).setColor(argb);
            buffer.addVertex(pose, bx, by, bz).setColor(argb);
            buffer.addVertex(pose, cx, cy, cz).setColor(argb);
            buffer.addVertex(pose, dx, dy, dz).setColor(argb);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hit Color
    // ---------------------------------------------------------------------------------------------

    /** Hook ({@code OverlayTexture}'s constructor): the texture whose top rows are the hurt tint. */
    public static void overlay(DynamicTexture texture) {
        overlay = texture;
        if (hitColorHeld) {
            paintOverlay(hitColor);
        }
    }

    /** {@code Game.overrideHitColor}'s: strength 0 gives the game's red back. */
    static void hitColor(int rgb, int strength) {
        boolean held = strength > 0;
        // The overlay's alpha is how much of the entity's own color stays: the strength's opposite.
        int argb = held
                ? (255 - Math.round(Math.min(100, strength) * 255 / 100.0F)) << 24 | (rgb & 0xFFFFFF)
                : GAME_HIT_COLOR;
        if (held == hitColorHeld && argb == hitColor) {
            return;
        }
        hitColorHeld = held;
        hitColor = argb;
        paintOverlay(argb);
    }

    private static void paintOverlay(int argb) {
        DynamicTexture texture = overlay;
        if (texture == null) {
            return;
        }
        NativeImage pixels = texture.getPixels();
        // The game's overlay: the hurt tint in the top half, the white flash's fade below.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                pixels.setPixel(x, y, argb);
            }
        }
        texture.upload();
    }

    // ---------------------------------------------------------------------------------------------
    // Enchant Glint
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code Game.overrideGlintColor}'s: the glint textures painted from the game's, in {@code rgb},
     * under the game's own names; -1 lets the game load its own again.
     */
    static void glintColor(int rgb) {
        if (rgb == glintColor) {
            return;
        }
        glintColor = rgb;
        for (Identifier id :
                new Identifier[] {ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR
                }) {
            if (rgb < 0) {
                // Released, the game loads it from the resource packs the next time it's drawn.
                Minecraft.getInstance().getTextureManager().release(id);
            } else {
                tintGlint(id, rgb);
            }
        }
    }

    private static void tintGlint(Identifier id, int rgb) {
        Minecraft.getInstance().getTextureManager().registerAndLoad(id, new TintedGlint(id, rgb));
    }

    /**
     * A glint texture in the chosen color: the game's own from the resource packs, its pixels
     * repainted as it loads. Loaded like the game's (its {@code .mcmeta}'s blur kept) and loaded
     * again with every resource reload, so a pack changed while it's held is colored too.
     */
    private static final class TintedGlint extends ReloadableTexture {

        private final int rgb;

        TintedGlint(Identifier id, int rgb) {
            super(id);
            this.rgb = rgb;
        }

        @Override
        public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
            TextureContents contents = TextureContents.load(resourceManager, resourceId());
            NativeImage image = contents.image();
            int r = (rgb >> 16) & 255;
            int g = (rgb >> 8) & 255;
            int b = rgb & 255;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixel(x, y);
                    // The glint is a purple shimmer: its brightness, in the chosen color.
                    int light = Math.max((pixel >> 16) & 255, Math.max((pixel >> 8) & 255, pixel & 255));
                    int out =
                            (pixel & 0xFF000000) | (r * light / 255) << 16 | (g * light / 255) << 8 | (b * light / 255);
                    image.setPixel(x, y, out);
                }
            }
            return contents;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hit & Kill Effects
    // ---------------------------------------------------------------------------------------------

    /** From the attack hook: the entity the player hit, kept for effects and to see it die. */
    static void attacked(Entity entity) {
        target = entity;
        hadTarget = true;
        Minecraft minecraft = Minecraft.getInstance();
        targetTick = minecraft.player == null ? 0 : minecraft.player.tickCount;
        remember(entity);
    }

    private static void remember(Entity entity) {
        lastX = entity.getX();
        lastY = entity.getY();
        lastZ = entity.getZ();
        lastWidth = entity.getBbWidth();
        lastHeight = entity.getBbHeight();
    }

    /** Once a tick: whether what the player hit has died since (the kill event). */
    static void tick(Minecraft minecraft, ModuleSystem system) {
        Entity hit = target;
        if (hit == null || minecraft.player == null) {
            return;
        }
        if (minecraft.player.tickCount - targetTick > KILL_TICKS) {
            target = null;
            return;
        }
        boolean dead = hit instanceof LivingEntity living ? living.isDeadOrDying() : hit.isRemoved();
        if (dead) {
            target = null;
            if (system.events().count(Events.KILL) > 0) {
                system.events().fireKill();
            }
        } else {
            remember(hit);
        }
    }

    /** From leaving a world: nothing hit in the next. */
    static void leave() {
        target = null;
        hadTarget = false;
    }

    /** {@code Game.effectOnTarget}'s. */
    static void effect(Effect effect, int count, boolean sound) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !hadTarget) {
            return;
        }
        Entity hit = target;
        if (hit != null && !hit.isRemoved()) {
            remember(hit);
        }
        RandomSource random = level.getRandom();
        ParticleOptions particle = particle(effect);
        float speed = speed(effect);
        AABB box = new AABB(
                lastX - lastWidth / 2,
                lastY,
                lastZ - lastWidth / 2,
                lastX + lastWidth / 2,
                lastY + lastHeight,
                lastZ + lastWidth / 2);
        for (int i = 0; i < count; i++) {
            double x = box.minX + random.nextDouble() * box.getXsize();
            double y = box.minY + box.getYsize() * (0.2 + random.nextDouble() * 0.7);
            double z = box.minZ + random.nextDouble() * box.getZsize();
            level.addParticle(
                    particle,
                    x,
                    y,
                    z,
                    (random.nextDouble() - 0.5) * speed,
                    random.nextDouble() * speed * 0.6,
                    (random.nextDouble() - 0.5) * speed);
        }
        if (sound) {
            SoundEvent event = sound(effect);
            level.playLocalSound(lastX, lastY + lastHeight / 2, lastZ, event, SoundSource.PLAYERS, 0.6F, 1.0F, false);
        }
    }

    private static ParticleOptions particle(Effect effect) {
        return switch (effect) {
            case CRITS -> ParticleTypes.CRIT;
            case MAGIC -> ParticleTypes.ENCHANTED_HIT;
            case SPARKS -> ParticleTypes.ELECTRIC_SPARK;
            case HEARTS -> ParticleTypes.HEART;
            case FLAMES -> ParticleTypes.FLAME;
            case SNOW -> ParticleTypes.SNOWFLAKE;
            case FIREWORK -> ParticleTypes.FIREWORK;
            case SOULS -> ParticleTypes.SOUL;
            case SMOKE -> ParticleTypes.POOF;
            case TOTEM -> ParticleTypes.TOTEM_OF_UNDYING;
        };
    }

    /** How fast the effect's particles fly apart. */
    private static float speed(Effect effect) {
        return switch (effect) {
            case FIREWORK, TOTEM -> 0.5F;
            case HEARTS, SOULS -> 0.05F;
            case SMOKE -> 0.1F;
            default -> 0.3F;
        };
    }

    private static SoundEvent sound(Effect effect) {
        return switch (effect) {
            case CRITS -> SoundEvents.PLAYER_ATTACK_CRIT;
            case MAGIC -> SoundEvents.ENCHANTMENT_TABLE_USE;
            case SPARKS -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case HEARTS -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case FLAMES -> SoundEvents.FIRECHARGE_USE;
            case SNOW -> SoundEvents.POWDER_SNOW_BREAK;
            case FIREWORK -> SoundEvents.FIREWORK_ROCKET_BLAST;
            case SOULS -> SoundEvents.SOUL_ESCAPE.value();
            case SMOKE -> SoundEvents.FIRE_EXTINGUISH;
            case TOTEM -> SoundEvents.TOTEM_USE;
        };
    }
}
