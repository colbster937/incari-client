package incari.client.modules;

import incari.framework.ModuleSystem;
import incari.framework.event.Events;
import incari.framework.event.FireOverlay;

/**
 * The fire overlay event ({@link Events#FIRE_OVERLAY}): how far modules move the flames drawn over
 * the view while the player burns (Low Fire). Render thread, each frame the flames are drawn.
 */
public final class ModuleOverlays {

    /** One object for every frame: the event is on the hot path. */
    private static final FireOverlay FIRE = new FireOverlay();

    private ModuleOverlays() {}

    /**
     * Hook ({@code ScreenEffectRenderer.submit}, before the flames): how far down to draw them, in
     * the overlay's units (its quads are one high).
     */
    public static float fireDrop() {
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.FIRE_OVERLAY) == 0) {
            return 0;
        }
        FIRE.reset();
        return system.events().fireFireOverlay(FIRE);
    }
}
