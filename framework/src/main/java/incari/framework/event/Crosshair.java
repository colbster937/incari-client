package incari.framework.event;

import incari.framework.hud.HudCanvas;
import org.jspecify.annotations.Nullable;

/**
 * The crosshair about to be drawn, as {@link Events#CROSSHAIR} hands it to listeners: where its
 * middle is, how big a screen pixel is, and the attack's state. A listener draws its own on {@link
 * #canvas()}; while anyone listens the game's isn't drawn. One object, reused every frame.
 */
public final class Crosshair {

    private @Nullable HudCanvas canvas;
    private float centerX;
    private float centerY;
    private float pixel;
    private float attackStrength;
    private boolean onTarget;

    /** The game's side: this frame's crosshair, before the listeners run. */
    public void reset(
            HudCanvas canvas, float centerX, float centerY, float pixel, float attackStrength, boolean onTarget) {
        this.canvas = canvas;
        this.centerX = centerX;
        this.centerY = centerY;
        this.pixel = pixel;
        this.attackStrength = attackStrength;
        this.onTarget = onTarget;
    }

    /** What to draw with, in GUI units. */
    public HudCanvas canvas() {
        HudCanvas current = canvas;
        if (current == null) {
            throw new IllegalStateException("No crosshair is being drawn");
        }
        return current;
    }

    /** The screen's middle across, in GUI units (a half when the screen's pixels are odd). */
    public float centerX() {
        return centerX;
    }

    /** The screen's middle down. */
    public float centerY() {
        return centerY;
    }

    /** One screen pixel in GUI units: 1 over the GUI scale. */
    public float pixel() {
        return pixel;
    }

    /** How charged the next attack is, 0 just swung to 1 ready. */
    public float attackStrength() {
        return attackStrength;
    }

    /** Whether the crosshair is on a living entity in reach. */
    public boolean onTarget() {
        return onTarget;
    }
}
