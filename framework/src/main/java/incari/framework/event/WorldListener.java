package incari.framework.event;

import incari.framework.hud.WorldCanvas;

/** Receives {@link Events#WORLD}. */
@FunctionalInterface
public interface WorldListener {

    /** Adds to the world drawn this frame. Hot path: don't allocate. */
    void onWorld(WorldCanvas canvas);
}
