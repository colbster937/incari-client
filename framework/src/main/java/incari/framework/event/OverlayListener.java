package incari.framework.event;

import incari.framework.hud.WorldOverlay;

/** Receives {@link Events#OVERLAY}. */
@FunctionalInterface
public interface OverlayListener {

    /** Draws over the world this frame. Hot path: don't allocate. */
    void onOverlay(WorldOverlay overlay);
}
