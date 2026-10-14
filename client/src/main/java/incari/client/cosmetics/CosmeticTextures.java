package incari.client.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import incari.online.Cosmetic;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The capes' textures and the wardrobe's icons (ADR 0061), painted by {@link CosmeticArt} the first
 * time each is needed. A moving cape is painted again ten times a second while something has drawn
 * it in the last two seconds, and left as it is otherwise: a cape nobody sees costs nothing.
 *
 * <p>Render thread (the game's thread in Incari's builds).
 */
final class CosmeticTextures {

    /** How long a moving cape goes on moving after it was last drawn. */
    private static final long IDLE_MILLIS = 2_000;

    /** A cape's texture, and when it was last drawn. */
    private static final class Cape {
        final Identifier id;
        final ClientAsset.Texture asset;
        final DynamicTexture texture;
        final int[] pixels = new int[CosmeticArt.CAPE_WIDTH * CosmeticArt.CAPE_HEIGHT];
        final boolean animated;
        long lastDrawn;

        Cape(Identifier id, DynamicTexture texture, boolean animated) {
            this.id = id;
            this.asset = new ClientAsset.ResourceTexture(id, id);
            this.texture = texture;
            this.animated = animated;
        }
    }

    private static final Map<String, Cape> CAPES = new HashMap<>();
    private static final Map<String, Identifier> ICONS = new HashMap<>();
    private static final long START = Util.getMillis();

    private CosmeticTextures() {}

    /** The cape's texture, as a skin's cape: made now if it's the first time. */
    static ClientAsset.Texture cape(Cosmetic cape) {
        Cape made = capeTexture(cape);
        made.lastDrawn = Util.getMillis();
        return made.asset;
    }

    /** The cape's texture by id, for the wardrobe's cards (which show its outer side). */
    static Identifier capeId(Cosmetic cape) {
        Cape made = capeTexture(cape);
        made.lastDrawn = Util.getMillis();
        return made.id;
    }

    private static Cape capeTexture(Cosmetic cape) {
        Cape made = CAPES.get(cape.id());
        if (made == null) {
            Identifier id = Identifier.fromNamespaceAndPath(
                    "incari", "cosmetics/" + cape.id().replace('.', '/'));
            NativeImage image = new NativeImage(CosmeticArt.CAPE_WIDTH, CosmeticArt.CAPE_HEIGHT, true);
            DynamicTexture texture = new DynamicTexture(id::toString, image);
            made = new Cape(id, texture, CosmeticArt.animated(cape.id()));
            paint(cape.id(), made);
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(id, texture);
            CAPES.put(cape.id(), made);
        }
        return made;
    }

    /** Once a client tick: moving capes drawn lately are painted again, every other tick. */
    static void tick(long tick) {
        if ((tick & 1) != 0 || CAPES.isEmpty()) {
            return;
        }
        long now = Util.getMillis();
        for (Map.Entry<String, Cape> each : CAPES.entrySet()) {
            Cape cape = each.getValue();
            if (cape.animated && now - cape.lastDrawn < IDLE_MILLIS) {
                paint(each.getKey(), cape);
                cape.texture.upload();
            }
        }
    }

    private static void paint(String id, Cape cape) {
        CosmeticArt.cape(id, (Util.getMillis() - START) / 1000.0F, cape.pixels);
        NativeImage image = cape.texture.getPixels();
        for (int y = 0; y < CosmeticArt.CAPE_HEIGHT; y++) {
            for (int x = 0; x < CosmeticArt.CAPE_WIDTH; x++) {
                image.setPixel(x, y, cape.pixels[y * CosmeticArt.CAPE_WIDTH + x]);
            }
        }
    }

    /** A hat's or back cosmetic's icon for its wardrobe card; null for one with none. */
    static @Nullable Identifier icon(Cosmetic cosmetic) {
        if (cosmetic.slot() != Cosmetic.Slot.HAT && cosmetic.slot() != Cosmetic.Slot.BACK) {
            return null;
        }
        Identifier id = ICONS.get(cosmetic.id());
        if (id == null) {
            int[] pixels = new int[CosmeticArt.ICON * CosmeticArt.ICON];
            CosmeticArt.icon(cosmetic.id(), pixels);
            NativeImage image = new NativeImage(CosmeticArt.ICON, CosmeticArt.ICON, true);
            for (int y = 0; y < CosmeticArt.ICON; y++) {
                for (int x = 0; x < CosmeticArt.ICON; x++) {
                    image.setPixel(x, y, pixels[y * CosmeticArt.ICON + x]);
                }
            }
            id = Identifier.fromNamespaceAndPath(
                    "incari", "cosmetics/icon/" + cosmetic.id().replace('.', '/'));
            Identifier label = id;
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(label::toString, image));
            ICONS.put(cosmetic.id(), id);
        }
        return id;
    }
}
