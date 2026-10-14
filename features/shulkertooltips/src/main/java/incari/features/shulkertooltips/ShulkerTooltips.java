package incari.features.shulkertooltips;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.ContainerTooltip;
import incari.framework.event.Events;
import incari.framework.setting.BooleanSetting;

/**
 * Shulker Tooltips: hovering a shulker box (or anything else holding items) shows what's inside as
 * a grid of items, nine across as in the box, in place of the game's first five lines of names.
 */
public final class ShulkerTooltips extends Module {

    final BooleanSetting tinted =
            settings().bool("tinted", "Box color", true).describedAs("The grid takes the box's dye color.");
    final BooleanSetting onlyWithShift = settings()
            .bool("only-with-shift", "Only with Shift", false)
            .describedAs("Shows the grid only while Shift is held; otherwise the game's lines.");

    /** Shulker Tooltips, off until the player turns it on. */
    public ShulkerTooltips() {
        super(
                "shulkertooltips",
                "Shulker Tooltips",
                "Shows a shulker box's contents as a grid in its tooltip.",
                Category.UTILITY);
        on(Events.CONTAINER_TOOLTIP, this::onTooltip);
    }

    private void onTooltip(ContainerTooltip tooltip) {
        if (onlyWithShift.get() && !tooltip.shiftDown()) {
            return;
        }
        tooltip.setPreview(true);
        tooltip.setTinted(tinted.get());
    }
}
