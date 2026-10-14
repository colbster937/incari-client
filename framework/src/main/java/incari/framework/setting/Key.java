package incari.framework.setting;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A key or a mouse button, in the game's own codes: keyboard keys by scancode (USB HID usage, as
 * SDL numbers them: 224 is Left Control, 229 Right Shift), mouse buttons from 0 (left). The config
 * writes it as {@code keyboard:224}, {@code mouse:3} or {@code none}.
 *
 * @param device which device the code is on
 * @param code the scancode or button; 0 on the keyboard is no key
 */
public record Key(Device device, int code) {

    /** No key: a binding that never fires. */
    public static final Key NONE = new Key(Device.KEYBOARD, 0);

    /** Which device a {@link Key} is on. */
    public enum Device {
        /** A keyboard key, by scancode. */
        KEYBOARD,
        /** A mouse button, from 0. */
        MOUSE
    }

    /** The keyboard key with this scancode. */
    public static Key keyboard(int scancode) {
        return new Key(Device.KEYBOARD, scancode);
    }

    /** The mouse button with this number (0 left, 1 right, 2 middle). */
    public static Key mouse(int button) {
        return new Key(Device.MOUSE, button);
    }

    /** Whether it's a real key, not {@link #NONE}. */
    public boolean isBound() {
        return !equals(NONE);
    }

    /** How the config writes it. */
    public String encode() {
        return isBound() ? device.name().toLowerCase(Locale.ROOT) + ":" + code : "none";
    }

    /** The key {@link #encode} wrote, or null if {@code text} isn't one. */
    public static @Nullable Key decode(String text) {
        if (text.equals("none")) {
            return NONE;
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            return null;
        }
        @Nullable
        Device device =
                switch (text.substring(0, colon)) {
                    case "keyboard" -> Device.KEYBOARD;
                    case "mouse" -> Device.MOUSE;
                    default -> null;
                };
        if (device == null) {
            return null;
        }
        try {
            int code = Integer.parseInt(text.substring(colon + 1));
            return code < 0 ? null : new Key(device, code);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
