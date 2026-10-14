package incari.framework.event;

/**
 * A tooltip about to be shown for an item holding others (a shulker box), as {@link
 * Events#CONTAINER_TOOLTIP} hands it to listeners. A listener may show the contents as a grid of
 * items instead of the game's lines of text. One object, reused.
 */
public final class ContainerTooltip {

    private boolean shiftDown;
    private boolean preview;
    private boolean tinted;

    /** The game's side: this tooltip, before the listeners run. */
    public void reset(boolean shiftDown) {
        this.shiftDown = shiftDown;
        this.preview = false;
        this.tinted = false;
    }

    /** Whether Shift is held as the tooltip shows. */
    public boolean shiftDown() {
        return shiftDown;
    }

    /** Whether the contents show as a grid of items, in place of the game's lines. */
    public boolean preview() {
        return preview;
    }

    /** Shows the contents as a grid of items. */
    public void setPreview(boolean on) {
        this.preview = on;
    }

    /** Whether the grid takes the box's dye color. */
    public boolean tinted() {
        return tinted;
    }

    public void setTinted(boolean on) {
        this.tinted = on;
    }
}
