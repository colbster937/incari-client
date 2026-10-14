package incari.features.glintcolor;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.setting.ColorSetting;

/**
 * Enchant Glint: the shimmer on enchanted items and armor in the player's color instead of purple.
 * Its speed and strength are the game's own accessibility options.
 */
public final class EnchantGlint extends Module {

    final ColorSetting color = settings().color("color", "Color", 0x7FC8FF).describedAs("The glint's color.");

    /** Enchant Glint, off until the player turns it on. */
    public EnchantGlint() {
        super("glintcolor", "Enchant Glint", "Colors the shimmer on enchanted items and armor.", Category.VISUAL);
        on(Events.TICK, this::apply);
    }

    private void apply() {
        system().game().overrideGlintColor(color.get() & 0xFFFFFF);
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        system().game().overrideGlintColor(-1);
    }
}
