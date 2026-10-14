package incari.features.lowshield;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.event.HeldItem;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.IntSetting;

/**
 * Low Shield: a shield held in first person is drawn lower, so it hides less of the view. Only the
 * drawing moves: blocking works as it did.
 */
public final class LowShield extends Module {

    /**
     * The view's units a percent moves the shield: 100% is the shield's height on screen (measured:
     * 0.16 units took a resting shield's top 30% of the screen down, to the screen's edge).
     */
    static final float UNITS_PER_PERCENT = 0.0018F;

    final IntSetting lower = settings()
            .integer("lower", "Lower by", 0, 90, 35)
            .formattedAs(percent -> percent + "%")
            .describedAs("How far down the shield is drawn, as a share of its height.");
    final BooleanSetting whileBlocking = settings()
            .bool("while-blocking", "Also while blocking", true)
            .describedAs("Off: a raised shield is drawn where the game draws it.");

    /** Low Shield, off until the player turns it on. */
    public LowShield() {
        super("lowshield", "Low Shield", "Draws the shield in your hand lower, so it hides less.", Category.VISUAL);
        on(Events.HELD_ITEM, this::onHeldItem);
    }

    private void onHeldItem(HeldItem item) {
        if (item.shield() && (whileBlocking.get() || !item.using())) {
            item.setLower(lower.get() * UNITS_PER_PERCENT);
        }
    }
}
