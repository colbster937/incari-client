package incari.features.lowfire;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.event.FireOverlay;
import incari.framework.setting.IntSetting;

/**
 * Low Fire: while the player burns, the flames over the view are drawn lower, so they hide less of
 * what's ahead. The player still sees they're on fire; only the overlay moves ({@link
 * Events#FIRE_OVERLAY}), not the fire itself or its damage.
 */
public final class LowFire extends Module {

    private final IntSetting lower = settings()
            // In flame heights: the game's flames reach about a third up the screen, gone at about 30%.
            .integer("lower", "Lower by", 0, 25, 10)
            .formattedAs(percent -> percent + "%")
            .describedAs("How far the flames move down: at 10% they keep to the bottom, at 20% only their tips show.");

    /** Low Fire, off until the player turns it on. */
    public LowFire() {
        super("lowfire", "Low Fire", "Draws the fire over your view lower, so it hides less.", Category.VISUAL);
        on(Events.FIRE_OVERLAY, this::onFireOverlay);
    }

    private void onFireOverlay(FireOverlay overlay) {
        overlay.setLower(lower.get() / 100.0F);
    }
}
