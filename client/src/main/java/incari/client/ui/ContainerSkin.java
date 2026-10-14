package incari.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The containers in the client's look, drawn by {@link FrostSkin} in place of their
 * background textures. The common ones, whose textures hold nothing but slots, a frame and an arrow
 * (the inventory, chests, shulker boxes, hoppers, dispensers, the crafting table, furnaces), become a
 * glass panel with a rounded well under each of the screen's slots and the arrow drawn again, their
 * labels light ({@link #label}). Every other container keeps its texture, cooled to a slate grey, so
 * what's drawn into it (an anvil's field, a villager's trades) stays where the game put it.
 *
 * <p>Render thread. Hot path: nothing allocates.
 */
public final class ContainerSkin {

    private static final Identifier INVENTORY = texture("inventory");
    private static final Identifier GENERIC = texture("generic_54");
    private static final Identifier SHULKER = texture("shulker_box");
    private static final Identifier HOPPER = texture("hopper");
    private static final Identifier DISPENSER = texture("dispenser");
    private static final Identifier CRAFTING = texture("crafting_table");
    private static final Identifier FURNACE = texture("furnace");
    private static final Identifier BLAST_FURNACE = texture("blast_furnace");
    private static final Identifier SMOKER = texture("smoker");

    /** A container texture kept, cooled: the game's light grey multiplied toward slate. */
    private static final int COOL = 0xFFB4C0CC;

    /** The screen drawn last as a glass panel, whose labels are light. */
    private static @Nullable Screen redrawn;

    private ContainerSkin() {}

    /** Whether the skin drew the texture: true for every container texture. */
    static boolean texture(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int width,
            int height,
            float u,
            float v,
            int srcWidth,
            int srcHeight,
            int textureWidth,
            int textureHeight,
            int color) {
        if (!texture.getPath().startsWith("textures/gui/container/")) {
            return false;
        }
        Screen screen = Minecraft.getInstance().gui.screen();
        AbstractContainerScreen<?> container = screen instanceof AbstractContainerScreen<?> c ? c : null;
        boolean generic = texture.equals(GENERIC);
        boolean replaced = container != null
                && (generic
                        || texture.equals(INVENTORY)
                        || texture.equals(SHULKER)
                        || texture.equals(HOPPER)
                        || texture.equals(DISPENSER)
                        || texture.equals(CRAFTING)
                        || texture.equals(FURNACE)
                        || texture.equals(BLAST_FURNACE)
                        || texture.equals(SMOKER));
        if (!replaced || container == null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    texture,
                    x,
                    y,
                    u,
                    v,
                    width,
                    height,
                    srcWidth,
                    srcHeight,
                    textureWidth,
                    textureHeight,
                    multiply(color, COOL));
            return true;
        }
        if (generic && v > 0) {
            // A chest's lower half (the player's inventory): the upper half's call drew the panel.
            return true;
        }
        redrawn = screen;
        // A chest's upper half is drawn first, alone: its panel reaches over the lower half's 96 rows.
        int panelHeight = generic ? height + 96 : height;
        Panels.darkPanel(graphics, x, y, width, panelHeight, 1.0F);
        java.util.List<Slot> slots = container.getMenu().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.isActive()) {
                int sx = x + slot.x - 1;
                int sy = y + slot.y - 1;
                RoundRects.box(graphics, sx, sy, 18, 18, 2, 0x59000000, 0x1AFFFFFF);
            }
        }
        if (texture.equals(INVENTORY)) {
            // The player's frame, and the arrow from the crafting grid to its result.
            RoundRects.fill(graphics, x + 26, y + 8, 49, 70, Theme.RADIUS, 0x40000000);
            Icons.ARROW.draw(graphics, x + 135, y + 28, 16, 0x99FFFFFF);
        } else if (texture.equals(CRAFTING)) {
            Icons.ARROW.draw(graphics, x + 90, y + 34, 22, 0x99FFFFFF);
        } else if (texture.equals(FURNACE) || texture.equals(BLAST_FURNACE) || texture.equals(SMOKER)) {
            Icons.ARROW.draw(graphics, x + 80, y + 31, 22, 0x66FFFFFF);
        }
        return true;
    }

    /**
     * {@code AbstractContainerScreen.extractLabels}: a label's color, light on the containers drawn
     * as glass, the game's dark grey on the rest.
     */
    @SuppressWarnings("ReferenceEquality") // the screen object drawn, not an equal one
    public static int label(Screen screen, int color) {
        return screen == redrawn && Skins.current() != null ? Theme.MUTED : color;
    }

    private static int multiply(int a, int b) {
        int alpha = (a >>> 24) * (b >>> 24) / 255;
        int red = (a >> 16 & 0xFF) * (b >> 16 & 0xFF) / 255;
        int green = (a >> 8 & 0xFF) * (b >> 8 & 0xFF) / 255;
        int blue = (a & 0xFF) * (b & 0xFF) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static Identifier texture(String name) {
        return Identifier.withDefaultNamespace("textures/gui/container/" + name + ".png");
    }
}
