package incari.client.ui;

/**
 * How much the GUI does to look good, chosen apart from the graphics preset: the same layout,
 * colors and font either way, with the effects that cost frames only in {@link #NORMAL}.
 */
public enum GuiPreset {
    /** Solid panels, no blur, no animation: every state change is instant. The default. */
    POTATO,
    /** Frosted, blurred menus, eased hover and press animations, a light sweep over buttons. */
    NORMAL
}
