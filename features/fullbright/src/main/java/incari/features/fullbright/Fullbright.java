package incari.features.fullbright;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.setting.IntSetting;

/**
 * Fullbright: caves and nights as light as day, on this client only. The game's lightmap is lit as
 * night vision lights it ({@code Game.overrideBrightness}), so there's no potion icon or particles,
 * and the Brightness slider stays the player's. A real night vision effect still shows if stronger.
 */
public final class Fullbright extends Module {

    final IntSetting level = settings()
            .integer("level", "Brightness", 10, 100, 100)
            .formattedAs(percent -> percent + "%")
            .describedAs("100%: everything fully lit, as with night vision.");

    /** Fullbright, off until the player turns it on. */
    public Fullbright() {
        super(
                "fullbright",
                "Fullbright",
                "Lights up caves and nights as if it were day, on your screen only.",
                Category.VISUAL);
        on(Events.TICK, this::apply);
    }

    private void apply() {
        system().game().overrideBrightness(level.get() / 100F);
    }

    @Override
    protected void onEnable() {
        apply();
    }

    @Override
    protected void onDisable() {
        system().game().overrideBrightness(0);
    }
}
