package incari.client.modules;

import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import incari.framework.ModuleSystem;
import incari.framework.event.ContainerTooltip;
import incari.framework.event.Events;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jspecify.annotations.Nullable;

/**
 * The container tooltip event ({@link Events#CONTAINER_TOOLTIP}): an item holding others (a
 * shulker box) shows its contents as a grid of items, nine across, on the box's color if asked, in
 * place of the game's first five lines of text. Render thread, as the pointer hovers.
 */
public final class ModuleTooltips {

    private static final ContainerTooltip TOOLTIP = new ContainerTooltip();
    private static final int COLUMNS = 9;
    private static final int SLOT = 18;
    private static final int PAD = 2;

    private ModuleTooltips() {}

    /** What a module said about {@code stack}'s tooltip; null if none previews it. */
    private static @Nullable ContainerTooltip ask(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null || contents.nonEmptyItemCopyStream().findAny().isEmpty()) {
            return null;
        }
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.CONTAINER_TOOLTIP) == 0) {
            return null;
        }
        TOOLTIP.reset(Minecraft.getInstance().hasShiftDown());
        system.events().fireContainerTooltip(TOOLTIP);
        return TOOLTIP.preview() ? TOOLTIP : null;
    }

    /** Hook ({@code ItemStack.getTooltipImage}): the grid, if a module shows one, else the game's. */
    public static Optional<TooltipComponent> image(ItemStack stack, Optional<TooltipComponent> game) {
        ContainerTooltip asked = ask(stack);
        if (asked == null) {
            return game;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return game;
        }
        int slots = Math.max(27, contents.size());
        NonNullList<ItemStack> items = NonNullList.withSize(slots, ItemStack.EMPTY);
        contents.copyInto(items);
        int tint = 0;
        if (asked.tinted()
                && stack.getItem() instanceof BlockItem block
                && block.getBlock() instanceof ShulkerBoxBlock box) {
            DyeColor color = box.getColor();
            tint = color == null ? 0xFF8E6A9A : color.getTextureDiffuseColor() | 0xFF000000;
        }
        return Optional.of(new Preview(items, tint));
    }

    /** Hook ({@code ItemContainerContents.addToTooltip}): whether to leave out the game's lines. */
    public static boolean hidesLines(ItemContainerContents contents) {
        // The tooltip's lines come before its image: the same answer as image() gives.
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.CONTAINER_TOOLTIP) == 0) {
            return false;
        }
        if (contents.nonEmptyItemCopyStream().findAny().isEmpty()) {
            return false;
        }
        TOOLTIP.reset(Minecraft.getInstance().hasShiftDown());
        system.events().fireContainerTooltip(TOOLTIP);
        return TOOLTIP.preview();
    }

    /** Hook ({@code ClientTooltipComponent.create}): the grid's drawing for a {@link Preview}. */
    public static ClientTooltipComponent client(Preview preview) {
        return new Grid(preview);
    }

    /** The contents, as the tooltip carries them. */
    public record Preview(NonNullList<ItemStack> items, int tint) implements TooltipComponent {}

    /** Nine across, in rows; each slot a darker well, the box's color behind them if tinted. */
    private record Grid(Preview preview) implements ClientTooltipComponent {

        private int rows() {
            return (preview.items().size() + COLUMNS - 1) / COLUMNS;
        }

        @Override
        public int getHeight(Font font) {
            return rows() * SLOT + PAD * 2 + 2;
        }

        @Override
        public int getWidth(Font font) {
            return COLUMNS * SLOT + PAD * 2;
        }

        @Override
        public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor graphics) {
            int width = getWidth(font);
            int height = rows() * SLOT + PAD * 2;
            int tint = preview.tint();
            int back = tint == 0 ? 0x40FFFFFF : (tint & 0x00FFFFFF) | 0x80000000;
            RoundRects.fill(graphics, x, y, width, height, Theme.RADIUS, back);
            for (int i = 0; i < preview.items().size(); i++) {
                int sx = x + PAD + i % COLUMNS * SLOT;
                int sy = y + PAD + i / COLUMNS * SLOT;
                graphics.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x55000000);
                ItemStack item = preview.items().get(i);
                if (!item.isEmpty()) {
                    graphics.item(item, sx + 1, sy + 1);
                    graphics.itemDecorations(font, item, sx + 1, sy + 1);
                }
            }
        }
    }
}
