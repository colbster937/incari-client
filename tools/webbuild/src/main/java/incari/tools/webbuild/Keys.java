package incari.tools.webbuild;

import incari.tools.build.CliException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keys as the DevTools protocol sends them: what a US keyboard reports for each
 * {@code KeyboardEvent.code} (its {@code key}, Windows virtual key code and typed text), and key
 * chords written the way browser check steps name them ({@code F3+KeyB}, {@code Control+KeyV}).
 */
final class Keys {

    /** The protocol's modifier bits. */
    static final int ALT = 1;

    static final int CTRL = 2;
    static final int META = 4;
    static final int SHIFT = 8;

    private Keys() {}

    /**
     * One key.
     *
     * @param location 1 for the left of a pair (Shift, Control...), 2 for the right, else 0
     */
    record Key(String key, int virtualKeyCode, String text, int location) {}

    /** The key at {@code code}, with {@code shift} held. */
    static Key of(String code, boolean shift) {
        if (code.length() == 4 && code.startsWith("Key") && Character.isUpperCase(code.charAt(3))) {
            char letter = code.charAt(3);
            String typed = String.valueOf(shift ? letter : Character.toLowerCase(letter));
            return new Key(typed, letter, typed, 0);
        }
        if (code.length() == 6 && code.startsWith("Digit") && Character.isDigit(code.charAt(5))) {
            char digit = code.charAt(5);
            String typed = shift ? String.valueOf(")!@#$%^&*(".charAt(digit - '0')) : String.valueOf(digit);
            return new Key(typed, digit, typed, 0);
        }
        if (code.matches("F([1-9]|1[0-9]|2[0-4])")) {
            return new Key(code, 111 + Integer.parseInt(code.substring(1)), "", 0);
        }
        return switch (code) {
            case "Enter" -> new Key("Enter", 13, "\r", 0);
            case "Escape" -> new Key("Escape", 27, "", 0);
            case "Backspace" -> new Key("Backspace", 8, "", 0);
            case "Tab" -> new Key("Tab", 9, "", 0);
            case "Space" -> new Key(" ", 32, " ", 0);
            case "Delete" -> new Key("Delete", 46, "", 0);
            case "Insert" -> new Key("Insert", 45, "", 0);
            case "Home" -> new Key("Home", 36, "", 0);
            case "End" -> new Key("End", 35, "", 0);
            case "PageUp" -> new Key("PageUp", 33, "", 0);
            case "PageDown" -> new Key("PageDown", 34, "", 0);
            case "ArrowLeft" -> new Key("ArrowLeft", 37, "", 0);
            case "ArrowUp" -> new Key("ArrowUp", 38, "", 0);
            case "ArrowRight" -> new Key("ArrowRight", 39, "", 0);
            case "ArrowDown" -> new Key("ArrowDown", 40, "", 0);
            case "ShiftLeft" -> new Key("Shift", 16, "", 1);
            case "ShiftRight" -> new Key("Shift", 16, "", 2);
            case "ControlLeft" -> new Key("Control", 17, "", 1);
            case "ControlRight" -> new Key("Control", 17, "", 2);
            case "AltLeft" -> new Key("Alt", 18, "", 1);
            case "AltRight" -> new Key("Alt", 18, "", 2);
            case "MetaLeft" -> new Key("Meta", 91, "", 1);
            case "MetaRight" -> new Key("Meta", 92, "", 2);
            case "Minus" -> symbol(shift, "-", "_", 189);
            case "Equal" -> symbol(shift, "=", "+", 187);
            case "BracketLeft" -> symbol(shift, "[", "{", 219);
            case "BracketRight" -> symbol(shift, "]", "}", 221);
            case "Backslash" -> symbol(shift, "\\", "|", 220);
            case "Semicolon" -> symbol(shift, ";", ":", 186);
            case "Quote" -> symbol(shift, "'", "\"", 222);
            case "Backquote" -> symbol(shift, "`", "~", 192);
            case "Comma" -> symbol(shift, ",", "<", 188);
            case "Period" -> symbol(shift, ".", ">", 190);
            case "Slash" -> symbol(shift, "/", "?", 191);
            default -> throw new CliException("No key '" + code + "' (use KeyboardEvent.code names, like KeyA or F3)");
        };
    }

    private static Key symbol(boolean shift, String plain, String shifted, int virtualKeyCode) {
        String typed = shift ? shifted : plain;
        return new Key(typed, virtualKeyCode, typed, 0);
    }

    /**
     * The codes of a chord, in the order they're pressed: {@code "Control+Shift+KeyT"}. Modifiers
     * may be written short ({@code Control}, {@code Ctrl}, {@code Shift}, {@code Alt}, {@code Meta})
     * for their left key.
     */
    static List<String> chord(String chord) {
        List<String> codes = new ArrayList<>();
        for (String part : chord.split("\\+", -1)) {
            String code =
                    switch (part.toLowerCase(Locale.ROOT)) {
                        case "control", "ctrl" -> "ControlLeft";
                        case "shift" -> "ShiftLeft";
                        case "alt" -> "AltLeft";
                        case "meta", "command" -> "MetaLeft";
                        default -> part;
                    };
            of(code, false);
            codes.add(code);
        }
        return codes;
    }

    /**
     * The key at {@code code} as SDL3 reports it on a US layout, for the desktop game's scene
     * agent: its scancode (the USB HID usage id) and its keycode (the character it types, or the
     * scancode with SDL's scancode-mask bit for keys that type nothing).
     */
    record SdlKey(int scancode, int keycode) {}

    private static final int SDLK_SCANCODE_MASK = 1 << 30;

    static SdlKey sdl(String code) {
        if (code.length() == 4 && code.startsWith("Key") && Character.isUpperCase(code.charAt(3))) {
            char letter = code.charAt(3);
            return new SdlKey(4 + letter - 'A', Character.toLowerCase(letter));
        }
        if (code.length() == 6 && code.startsWith("Digit") && Character.isDigit(code.charAt(5))) {
            char digit = code.charAt(5);
            return new SdlKey(digit == '0' ? 39 : 30 + digit - '1', digit);
        }
        if (code.matches("F([1-9]|1[0-2])")) {
            int scancode = 57 + Integer.parseInt(code.substring(1));
            return new SdlKey(scancode, scancode | SDLK_SCANCODE_MASK);
        }
        return switch (code) {
            case "Enter" -> new SdlKey(40, '\r');
            case "Escape" -> new SdlKey(41, 0x1B);
            case "Backspace" -> new SdlKey(42, 8);
            case "Tab" -> new SdlKey(43, '\t');
            case "Space" -> new SdlKey(44, ' ');
            case "ArrowRight" -> new SdlKey(79, 79 | SDLK_SCANCODE_MASK);
            case "ArrowLeft" -> new SdlKey(80, 80 | SDLK_SCANCODE_MASK);
            case "ArrowDown" -> new SdlKey(81, 81 | SDLK_SCANCODE_MASK);
            case "ArrowUp" -> new SdlKey(82, 82 | SDLK_SCANCODE_MASK);
            case "ControlLeft" -> new SdlKey(224, 224 | SDLK_SCANCODE_MASK);
            case "ShiftLeft" -> new SdlKey(225, 225 | SDLK_SCANCODE_MASK);
            case "AltLeft" -> new SdlKey(226, 226 | SDLK_SCANCODE_MASK);
            default -> throw new CliException("The desktop scene agent has no key '" + code + "'");
        };
    }

    /** The modifier bit a key sets while it's held, or 0. */
    static int modifierBit(String code) {
        return switch (code) {
            case "ShiftLeft", "ShiftRight" -> SHIFT;
            case "ControlLeft", "ControlRight" -> CTRL;
            case "AltLeft", "AltRight" -> ALT;
            case "MetaLeft", "MetaRight" -> META;
            default -> 0;
        };
    }
}
