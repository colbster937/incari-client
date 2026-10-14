package incari.platform.api.input;

/**
 * Physical key codes. A scancode names a key by where it is, not what it types: the key right of
 * Tab is {@link #Q} on every layout. The values are USB HID keyboard usage ids (HID usage table,
 * page 0x07), which is also what SDL3's scancodes and the game's key bindings use. The browser
 * maps {@code KeyboardEvent.code} onto them.
 *
 * <p>Only the codes Incari's own code needs are named here; every HID usage id is a valid
 * scancode.
 */
public final class Scancodes {

    public static final int UNKNOWN = 0;
    public static final int A = 4;
    /** Letters run contiguously from A (4) to Z (29). */
    public static final int Z = 29;
    /** Digits run from 1 (30) to 9 (38), then 0 (39). */
    public static final int DIGIT_1 = 30;

    public static final int DIGIT_0 = 39;
    public static final int Q = 20;
    public static final int W = 26;
    public static final int RETURN = 40;
    public static final int ESCAPE = 41;
    public static final int BACKSPACE = 42;
    public static final int TAB = 43;
    public static final int SPACE = 44;
    public static final int F1 = 58;
    /** Function keys run contiguously from F1 (58) to F12 (69). */
    public static final int F12 = 69;

    public static final int RIGHT = 79;
    public static final int LEFT = 80;
    public static final int DOWN = 81;
    public static final int UP = 82;
    public static final int LEFT_CTRL = 224;
    public static final int LEFT_SHIFT = 225;
    public static final int LEFT_ALT = 226;
    public static final int LEFT_SUPER = 227;
    public static final int RIGHT_CTRL = 228;
    public static final int RIGHT_SHIFT = 229;
    public static final int RIGHT_ALT = 230;
    public static final int RIGHT_SUPER = 231;

    /** One past the largest scancode: the size of a table indexed by scancode. */
    public static final int COUNT = 512;

    private Scancodes() {}
}
