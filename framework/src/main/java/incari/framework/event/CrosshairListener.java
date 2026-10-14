package incari.framework.event;

/** Receives {@link Events#CROSSHAIR}. */
@FunctionalInterface
public interface CrosshairListener {

    /** The crosshair is to be drawn: draw one on its canvas. */
    void onCrosshair(Crosshair crosshair);
}
