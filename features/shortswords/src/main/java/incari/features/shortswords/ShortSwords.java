package incari.features.shortswords;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.event.HeldItem;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.IntSetting;

/**
 * Short Swords: a sword held in first person is drawn smaller, toward the hand, as 1.8 PvP packs
 * drew them, so it hides less of the view. Only the drawing changes: reach and hits are the game's.
 */
public final class ShortSwords extends Module {

    final IntSetting size = settings()
            .integer("size", "Size", 40, 100, 70)
            .formattedAs(percent -> percent + "%")
            .describedAs("How big swords are drawn, against the game's size.");
    final BooleanSetting whileBlocking = settings()
            .bool("while-blocking", "Also while blocking", true)
            .describedAs("Off: a sword raised to block (1.7 Animations) is drawn at the game's size.");

    /** Short Swords, off until the player turns it on. */
    public ShortSwords() {
        super(
                "shortswords",
                "Short Swords",
                "Draws the sword in your hand smaller, so it hides less.",
                Category.VISUAL);
        on(Events.HELD_ITEM, this::onHeldItem);
    }

    private void onHeldItem(HeldItem item) {
        if (item.sword() && (whileBlocking.get() || !item.using())) {
            item.setScale(size.get() / 100.0F);
        }
    }
}
