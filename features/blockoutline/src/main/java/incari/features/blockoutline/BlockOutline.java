package incari.features.blockoutline;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.FloatSetting;
import incari.framework.setting.IntSetting;

/**
 * Block Outline: the outline round the block the crosshair is on, in the player's color and width,
 * its faces filled faintly if they like. The game's thin dark line comes back when it's off.
 */
public final class BlockOutline extends Module {

    final ColorSetting color = settings().color("color", "Color", 0xA9DCFF).describedAs("The outline's color.");
    final IntSetting opacity = settings()
            .integer("opacity", "Opacity", 10, 100, 90)
            .formattedAs(value -> value + "%")
            .describedAs("How solid the outline is.");
    final FloatSetting width =
            settings().decimal("width", "Width", 1, 6, 0.5F, 2.5F).describedAs("How thick the outline is, in pixels.");
    final BooleanSetting fill = settings().bool("fill", "Fill", false).describedAs("Shades the block's faces too.");
    final ColorSetting fillColor = settings()
            .color("fill-color", "Fill color", 0xA9DCFF)
            .describedAs("The shade's color.")
            .visibleWhen(fill::get);
    final IntSetting fillOpacity = settings()
            .integer("fill-opacity", "Fill opacity", 5, 60, 20)
            .formattedAs(value -> value + "%")
            .describedAs("How strong the shade is.")
            .visibleWhen(fill::get);

    /** Block Outline, off until the player turns it on. */
    public BlockOutline() {
        super(
                "blockoutline",
                "Block Outline",
                "Draws the outline of the block you look at in your own color and width.",
                Category.VISUAL);
        on(Events.TICK, this::apply);
    }

    private void apply() {
        int fillArgb = fill.get() ? withOpacity(fillColor.get(), fillOpacity.get()) : 0;
        system().game().overrideBlockOutline(withOpacity(color.get(), opacity.get()), width.get(), fillArgb);
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        system().game().overrideBlockOutline(0, 0, 0);
    }

    /** {@code argb}'s color at {@code percent} opacity, never fully clear (0 means the game's own). */
    static int withOpacity(int argb, int percent) {
        int alpha = Math.max(1, Math.round(percent * 255 / 100.0F));
        return alpha << 24 | (argb & 0xFFFFFF);
    }
}
