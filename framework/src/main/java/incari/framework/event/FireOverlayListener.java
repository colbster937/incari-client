package incari.framework.event;

/** Receives {@link Events#FIRE_OVERLAY}. */
@FunctionalInterface
public interface FireOverlayListener {

    /** The fire overlay is about to be drawn; change {@code overlay} to move it. */
    void onFireOverlay(FireOverlay overlay);
}
