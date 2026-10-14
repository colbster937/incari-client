package incari.features.hitcolor;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.IntSetting;

/**
 * Hit Color: the flash a mob or player gives when hurt, in the player's color instead of the
 * game's red. On this client only.
 */
public final class HitColor extends Module {

    final ColorSetting color =
            settings().color("color", "Color", 0x5AB8FF).describedAs("The color hurt mobs and players flash.");
    final IntSetting strength = settings()
            .integer("strength", "Strength", 10, 100, 30)
            .formattedAs(value -> value + "%")
            .describedAs("How strongly they're tinted. The game's red is 30%.");

    /** Hit Color, off until the player turns it on. */
    public HitColor() {
        super("hitcolor", "Hit Color", "Changes the color hurt mobs and players flash.", Category.VISUAL);
        on(Events.TICK, this::apply);
    }

    private void apply() {
        system().game().overrideHitColor(color.get() & 0xFFFFFF, strength.get());
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        system().game().overrideHitColor(0, 0);
    }
}
