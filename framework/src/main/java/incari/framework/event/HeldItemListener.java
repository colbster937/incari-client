package incari.framework.event;

/** Receives {@link Events#HELD_ITEM}. */
@FunctionalInterface
public interface HeldItemListener {

    /** A hand's item is about to be drawn in first person. */
    void onHeldItem(HeldItem item);
}
