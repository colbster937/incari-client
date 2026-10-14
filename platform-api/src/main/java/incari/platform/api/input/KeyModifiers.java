package incari.platform.api.input;

/**
 * Modifier key bits, as reported with key, mouse button and wheel events and by
 * {@link PlatformInput#modifiers}. The values are SDL3's {@code SDL_Keymod} values, which is the
 * format the game's key handling reads; the browser maps its own modifier flags onto them.
 */
public final class KeyModifiers {

    public static final int LEFT_SHIFT = 0x0001;
    public static final int RIGHT_SHIFT = 0x0002;
    public static final int LEFT_CTRL = 0x0040;
    public static final int RIGHT_CTRL = 0x0080;
    public static final int LEFT_ALT = 0x0100;
    public static final int RIGHT_ALT = 0x0200;
    /** The Windows key, Command on macOS. */
    public static final int LEFT_SUPER = 0x0400;

    public static final int RIGHT_SUPER = 0x0800;
    public static final int NUM_LOCK = 0x1000;
    public static final int CAPS_LOCK = 0x2000;

    public static final int SHIFT = LEFT_SHIFT | RIGHT_SHIFT;
    public static final int CTRL = LEFT_CTRL | RIGHT_CTRL;
    public static final int ALT = LEFT_ALT | RIGHT_ALT;
    public static final int SUPER = LEFT_SUPER | RIGHT_SUPER;

    private KeyModifiers() {}
}
