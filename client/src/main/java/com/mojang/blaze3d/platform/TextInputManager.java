package com.mojang.blaze3d.platform;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Incari's replacement for Mojang's text input manager (patches/classes.txt): turns text input on
 * for whichever widget has focus, and tells the input method where that widget is.
 */
public class TextInputManager {

    private final Window window;
    /** The widget text input is on for; only it may turn it off again. */
    private @Nullable Object owner;

    private boolean active;
    private @Nullable Area area;

    /** The text area in window coordinates. */
    private record Area(int x, int y, int width, int height) {}

    public TextInputManager(final Window window) {
        this.window = window;
    }

    /**
     * Sets where the text being edited is, in GUI coordinates (corners x0,y0 and x1,y1). The
     * platform wants window coordinates: GUI units times the GUI scale gives pixels, and pixels
     * over the pixel density gives window coordinates.
     */
    public void setTextInputArea(final int x0, final int y0, final int x1, final int y1) {
        double toWindow = this.window.getGuiScale() / (double) this.window.getPixelDensity();
        Area updated = new Area(
                (int) Math.round(x0 * toWindow),
                (int) Math.round(y0 * toWindow),
                Math.max(1, (int) Math.round((x1 - x0) * toWindow)),
                Math.max(1, (int) Math.round((y1 - y0) * toWindow)));
        if (!Objects.equals(updated, this.area)) {
            this.area = updated;
            this.window.platformWindow().setTextInputArea(updated.x(), updated.y(), updated.width(), updated.height());
        }
    }

    public void startTextInput(final Object newOwner) {
        this.owner = newOwner;
        if (!this.active) {
            this.active = true;
            this.window.platformWindow().startTextInput();
        }
    }

    /** Turns text input off, if {@code previousOwner} is the widget that turned it on. */
    // Identity is the point: the owner is one particular widget, whatever its equals says.
    @SuppressWarnings("ReferenceEquality")
    public void stopTextInput(final Object previousOwner) {
        if (this.owner == previousOwner) {
            this.stopTextInput();
        }
    }

    public void stopTextInput() {
        this.owner = null;
        if (this.active) {
            this.active = false;
            this.window.platformWindow().stopTextInput();
        }
    }

    public void onTextInputFocusChange(final Object widget, final boolean focused) {
        if (focused) {
            this.startTextInput(widget);
        } else {
            this.stopTextInput(widget);
        }
    }
}
