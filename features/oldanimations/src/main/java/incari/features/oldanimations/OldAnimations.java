package incari.features.oldanimations;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.Events;
import incari.framework.event.HeldItem;
import incari.framework.setting.BooleanSetting;

/**
 * 1.7 Animations: first person as 1.7 drew it. The block-hit (attacking while blocking, eating or
 * drawing a bow swings the arm, as it did before 1.8), flat items held smaller and tilted, the
 * fishing rod the other way round, and 1.7's sword-blocking pose on servers where swords block.
 * Only the drawing changes: nothing more is sent to the server.
 */
public final class OldAnimations extends Module {

    final BooleanSetting blockHit = settings()
            .bool("block-hit", "Block-hit swing", true)
            .describedAs("Attacking while an item's in use swings your arm, as 1.7 drew it.");
    final BooleanSetting items = settings()
            .bool("items", "1.7 item positions", true)
            .describedAs("Swords, tools and other flat items held as in 1.7.");
    final BooleanSetting rod = settings()
            .bool("rod", "1.7 fishing rod", true)
            .describedAs("The fishing rod points the way it did in 1.7.");
    final BooleanSetting swordBlock = settings()
            .bool("sword-block", "1.7 sword blocking", true)
            .describedAs("A blocking sword takes 1.7's pose (on servers where swords block).");

    /** 1.7 Animations, off until the player turns it on. */
    public OldAnimations() {
        super(
                "oldanimations",
                "1.7 Animations",
                "Draws your hands and items in first person as 1.7 did.",
                Category.VISUAL);
        on(Events.HELD_ITEM, this::onHeldItem);
    }

    private void onHeldItem(HeldItem item) {
        item.setSwingWhileUsing(blockHit.get());
        item.setLegacyItems(items.get());
        item.setLegacyRod(rod.get());
        item.setLegacyBlock(swordBlock.get());
    }
}
