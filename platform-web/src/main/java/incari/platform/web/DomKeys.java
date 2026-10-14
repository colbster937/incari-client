package incari.platform.web;

import incari.platform.api.input.Scancodes;
import org.jspecify.annotations.Nullable;

/**
 * Maps the browser's keys to the platform's. {@code KeyboardEvent.code} names the physical key,
 * the same on every layout, and becomes a scancode: a USB HID usage id (HID usage tables,
 * keyboard page 0x07), so {@code "KeyQ"} is the key right of Tab whatever it types.
 * {@code KeyboardEvent.key} is what the key means in the player's layout, and becomes an SDL3
 * keycode, the "shortcut key" the game's text fields read (Ctrl+V is keycode {@code 'v'}).
 */
final class DomKeys {

    /** SDL's keycode flag for keys that type nothing: the scancode with this bit set. */
    static final int NON_PRINTABLE = 1 << 30;

    /** HID usage 0x9C, Keyboard Clear: what the keypad's 5 means with Num Lock off. */
    private static final int CLEAR = 156;

    /** HID usage 0x49: the first of the navigation block, which runs from Insert to the arrows. */
    private static final int INSERT = 73;

    private DomKeys() {}

    /**
     * The scancode for a {@code KeyboardEvent.code}, or {@link Scancodes#UNKNOWN}. Every code in
     * the UI Events code tables that SDL3 has a scancode for is mapped; the rest (Fn, the launch
     * keys, legacy codes like Hyper) stay unknown, as they are to SDL.
     */
    static int scancode(String code) {
        if (code.length() == 4 && code.startsWith("Key")) {
            char letter = code.charAt(3);
            if (letter >= 'A' && letter <= 'Z') {
                return Scancodes.A + (letter - 'A');
            }
        }
        if (code.length() == 6 && code.startsWith("Digit")) {
            int digit = digit(code.charAt(5));
            if (digit >= 0) {
                return digit == 0 ? Scancodes.DIGIT_0 : Scancodes.DIGIT_1 + digit - 1;
            }
        }
        if (code.length() == 7 && code.startsWith("Numpad")) {
            int digit = digit(code.charAt(6));
            if (digit >= 0) {
                // HID puts the keypad's 1-9 at 0x59-0x61 and its 0 after them, at 0x62.
                return digit == 0 ? 98 : 89 + digit - 1;
            }
        }
        if (code.length() >= 2 && code.length() <= 3 && code.charAt(0) == 'F') {
            int number = parsePositive(code.substring(1));
            if (number >= 1 && number <= 12) {
                return Scancodes.F1 + number - 1;
            }
            if (number >= 13 && number <= 24) {
                // F13-F24 are a separate run, 0x68-0x73.
                return 104 + number - 13;
            }
        }
        return switch (code) {
            case "Enter" -> Scancodes.RETURN;
            case "Escape" -> Scancodes.ESCAPE;
            case "Backspace" -> Scancodes.BACKSPACE;
            case "Tab" -> Scancodes.TAB;
            case "Space" -> Scancodes.SPACE;
            case "Minus" -> 45;
            case "Equal" -> 46;
            case "BracketLeft" -> 47;
            case "BracketRight" -> 48;
            // Also the ISO key left of Enter (HID's "Non-US #"): browsers name both "Backslash".
            case "Backslash" -> 49;
            case "Semicolon" -> 51;
            case "Quote" -> 52;
            case "Backquote" -> 53;
            case "Comma" -> 54;
            case "Period" -> 55;
            case "Slash" -> 56;
            case "CapsLock" -> 57;
            case "PrintScreen" -> 70;
            case "ScrollLock" -> 71;
            case "Pause" -> 72;
            case "Insert" -> 73;
            case "Home" -> 74;
            case "PageUp" -> 75;
            case "Delete" -> 76;
            case "End" -> 77;
            case "PageDown" -> 78;
            case "ArrowRight" -> Scancodes.RIGHT;
            case "ArrowLeft" -> Scancodes.LEFT;
            case "ArrowDown" -> Scancodes.DOWN;
            case "ArrowUp" -> Scancodes.UP;
            case "NumLock" -> 83;
            case "NumpadDivide" -> 84;
            case "NumpadMultiply" -> 85;
            case "NumpadSubtract" -> 86;
            case "NumpadAdd" -> 87;
            case "NumpadEnter" -> 88;
            case "NumpadDecimal" -> 99;
            // The ISO key right of the left Shift.
            case "IntlBackslash" -> 100;
            case "ContextMenu" -> 101;
            case "Power" -> 102;
            case "NumpadEqual" -> 103;
            case "Open" -> 116;
            case "Help" -> 117;
            case "Select" -> 119;
            case "Again" -> 121;
            case "Undo" -> 122;
            case "Cut" -> 123;
            case "Copy" -> 124;
            case "Paste" -> 125;
            case "Find" -> 126;
            // Older Firefox used the shorter names.
            case "AudioVolumeMute", "VolumeMute" -> 127;
            case "AudioVolumeUp", "VolumeUp" -> 128;
            case "AudioVolumeDown", "VolumeDown" -> 129;
            case "NumpadComma" -> 133;
            case "IntlRo" -> 135;
            case "KanaMode" -> 136;
            case "IntlYen" -> 137;
            case "Convert" -> 138;
            case "NonConvert" -> 139;
            case "Lang1" -> 144;
            case "Lang2" -> 145;
            case "Lang3" -> 146;
            case "Lang4" -> 147;
            case "Lang5" -> 148;
            // The legacy names of Lang3 and Lang4.
            case "Katakana" -> 146;
            case "Hiragana" -> 147;
            case "NumpadParenLeft" -> 182;
            case "NumpadParenRight" -> 183;
            case "NumpadBackspace" -> 187;
            // Phone keypads' "#" and "*".
            case "NumpadHash" -> 204;
            case "NumpadStar" -> 85;
            case "NumpadMemoryStore" -> 208;
            case "NumpadMemoryRecall" -> 209;
            case "NumpadMemoryClear" -> 210;
            case "NumpadMemoryAdd" -> 211;
            case "NumpadMemorySubtract" -> 212;
            case "NumpadClear" -> 216;
            case "NumpadClearEntry" -> 217;
            // Media and browser keys: HID's consumer page, which SDL3 numbers from 258 on. They have
            // no HID keyboard usage, so SDL3's own values are the scancodes.
            case "Sleep" -> 258;
            case "WakeUp" -> 259;
            case "MediaPlay" -> 262;
            case "MediaPause" -> 263;
            case "MediaRecord" -> 264;
            case "MediaFastForward" -> 265;
            case "MediaRewind" -> 266;
            case "MediaTrackNext" -> 267;
            case "MediaTrackPrevious" -> 268;
            case "MediaStop" -> 269;
            case "Eject" -> 270;
            case "MediaPlayPause" -> 271;
            case "MediaSelect", "LaunchMediaPlayer" -> 272;
            case "BrowserSearch" -> 280;
            case "BrowserHome" -> 281;
            case "BrowserBack" -> 282;
            case "BrowserForward" -> 283;
            case "BrowserStop" -> 284;
            case "BrowserRefresh" -> 285;
            case "BrowserFavorites" -> 286;
            case "ControlLeft" -> Scancodes.LEFT_CTRL;
            case "ShiftLeft" -> Scancodes.LEFT_SHIFT;
            case "AltLeft" -> Scancodes.LEFT_ALT;
            // "OSLeft"/"OSRight": Firefox before version 118.
            case "MetaLeft", "OSLeft" -> Scancodes.LEFT_SUPER;
            case "ControlRight" -> Scancodes.RIGHT_CTRL;
            case "ShiftRight" -> Scancodes.RIGHT_SHIFT;
            case "AltRight" -> Scancodes.RIGHT_ALT;
            case "MetaRight", "OSRight" -> Scancodes.RIGHT_SUPER;
            default -> Scancodes.UNKNOWN;
        };
    }

    /**
     * SDL3's keycode for a key event: the character the key types in the player's layout without
     * Shift or AltGr, or the scancode with {@link #NON_PRINTABLE} for keys that type nothing.
     *
     * <p>The browser's {@code key} already includes Shift and AltGr ("!" for Shift+1, "@" for
     * AltGr+Q on a German keyboard), while SDL's keycode doesn't. So the unmodified character is
     * remembered per key in {@code learned} whenever the key is pressed without them, and used
     * when it's pressed with them; until then a US layout's character stands in. Like SDL3's
     * default keycode options, digit keys always give digits (French keyboards type "&" there
     * unshifted) and letter keys on non-Latin layouts give the US letter, so Ctrl+C works on a
     * Russian keyboard.
     *
     * @param code {@code KeyboardEvent.code}
     * @param scancode {@link #scancode} of {@code code}
     * @param key {@code KeyboardEvent.key}
     * @param levelShifted whether Shift, AltGr or Alt changed what {@code key} says
     * @param learned the unmodified character seen for each scancode, 0 if none yet; updated here
     */
    static int keyCode(String code, int scancode, String key, boolean levelShifted, int[] learned) {
        int character = singleCodePoint(key);
        if (code.startsWith("Numpad")) {
            // The keypad has keycodes of its own, even for the keys that type digits. With Num
            // Lock off the browser reports what the key does instead ("End" for 1), as SDL does.
            // Enter stays the keypad's own Enter.
            int navigation = "Clear".equals(key) ? CLEAR : scancode(key);
            if (navigation == CLEAR || (navigation >= INSERT && navigation <= Scancodes.UP)) {
                return namedKeyCode(key, navigation);
            }
            return scancode | NON_PRINTABLE;
        }
        if (character < 0 || isControl(character)) {
            return namedKeyCode(key, scancode);
        }
        if (scancode >= Scancodes.DIGIT_1 && scancode <= Scancodes.DIGIT_0) {
            return scancode == Scancodes.DIGIT_0 ? '0' : '1' + scancode - Scancodes.DIGIT_1;
        }
        int lower = Character.toLowerCase(character);
        if (scancode >= Scancodes.A && scancode <= Scancodes.Z) {
            int usLetter = 'a' + scancode - Scancodes.A;
            if (Character.isLetter(lower)) {
                // Caps Lock and Shift only change the case of a letter.
                return isLatin(lower) ? lower : usLetter;
            }
            if (!levelShifted) {
                // A letter key that types a symbol: a layout like Dvorak's.
                remember(learned, scancode, lower);
                return lower;
            }
            return fallback(learned, scancode, usLetter);
        }
        if (!levelShifted) {
            remember(learned, scancode, lower);
            return lower;
        }
        return fallback(learned, scancode, usCharacter(scancode, lower));
    }

    /** The keycode for a key the browser names by a word, like "Enter" or "ArrowUp". */
    private static int namedKeyCode(String key, int scancode) {
        return switch (key) {
            case "Enter" -> '\r';
            case "Escape" -> 0x1B;
            case "Backspace" -> '\b';
            case "Tab" -> '\t';
            case "Delete" -> 0x7F;
            default -> scancode | NON_PRINTABLE;
        };
    }

    /**
     * What a key types on a US keyboard, unshifted, as its label: for the letter, digit and
     * punctuation keys, whose names depend on the layout; null for the rest.
     */
    static @Nullable String usName(int scancode) {
        int character;
        if (scancode >= Scancodes.A && scancode <= Scancodes.Z) {
            character = 'a' + scancode - Scancodes.A;
        } else if (scancode >= Scancodes.DIGIT_1 && scancode <= Scancodes.DIGIT_0) {
            character = scancode == Scancodes.DIGIT_0 ? '0' : '1' + scancode - Scancodes.DIGIT_1;
        } else if (scancode >= 45 && scancode <= 56 && scancode != 50) {
            character = usCharacter(scancode, 0);
        } else {
            return null;
        }
        return String.valueOf((char) character);
    }

    /** What an unshifted US layout types on the keys whose character a layout can change. */
    private static int usCharacter(int scancode, int otherwise) {
        return switch (scancode) {
            case Scancodes.SPACE -> ' ';
            case 45 -> '-';
            case 46 -> '=';
            case 47 -> '[';
            case 48 -> ']';
            case 49 -> '\\';
            case 51 -> ';';
            case 52 -> '\'';
            case 53 -> '`';
            case 54 -> ',';
            case 55 -> '.';
            case 56 -> '/';
            default -> otherwise;
        };
    }

    private static void remember(int[] learned, int scancode, int character) {
        if (scancode > 0 && scancode < learned.length) {
            learned[scancode] = character;
        }
    }

    private static int fallback(int[] learned, int scancode, int otherwise) {
        if (scancode > 0 && scancode < learned.length && learned[scancode] != 0) {
            return learned[scancode];
        }
        return otherwise;
    }

    /** The one code point {@code text} holds, or -1 if it holds none or several. */
    static int singleCodePoint(String text) {
        if (text.length() == 1) {
            return text.charAt(0);
        }
        if (text.length() == 2 && Character.isSurrogatePair(text.charAt(0), text.charAt(1))) {
            return text.codePointAt(0);
        }
        return -1;
    }

    private static boolean isControl(int character) {
        return character < 0x20 || (character >= 0x7F && character < 0xA0);
    }

    /**
     * Latin letters: Basic Latin through Latin Extended-B, and Latin Extended Additional. Enough
     * to tell a Latin layout (AZERTY, QWERTZ, Nordic) from a Cyrillic, Greek or Thai one.
     */
    private static boolean isLatin(int letter) {
        return letter < 0x250 || (letter >= 0x1E00 && letter < 0x1F00);
    }

    private static int digit(char c) {
        return c >= '0' && c <= '9' ? c - '0' : -1;
    }

    private static int parsePositive(String digits) {
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digit(digits.charAt(i));
            if (d < 0) {
                return -1;
            }
            value = value * 10 + d;
        }
        return digits.isEmpty() ? -1 : value;
    }
}
