package incari.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.input.Scancodes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomKeysTest {

    private static final int NP = DomKeys.NON_PRINTABLE;

    /** Every value checked against the HID usage tables, keyboard/keypad page (0x07). */
    @ParameterizedTest
    @CsvSource({
        "KeyA, 4",
        "KeyQ, 20",
        "KeyW, 26",
        "KeyZ, 29",
        "Digit1, 30",
        "Digit9, 38",
        "Digit0, 39",
        "Enter, 40",
        "Escape, 41",
        "Backspace, 42",
        "Tab, 43",
        "Space, 44",
        "Minus, 45",
        "Equal, 46",
        "BracketLeft, 47",
        "BracketRight, 48",
        "Backslash, 49",
        "Semicolon, 51",
        "Quote, 52",
        "Backquote, 53",
        "Comma, 54",
        "Period, 55",
        "Slash, 56",
        "CapsLock, 57",
        "F1, 58",
        "F10, 67",
        "F12, 69",
        "PrintScreen, 70",
        "ScrollLock, 71",
        "Pause, 72",
        "Insert, 73",
        "Home, 74",
        "PageUp, 75",
        "Delete, 76",
        "End, 77",
        "PageDown, 78",
        "ArrowRight, 79",
        "ArrowLeft, 80",
        "ArrowDown, 81",
        "ArrowUp, 82",
        "NumLock, 83",
        "NumpadDivide, 84",
        "NumpadMultiply, 85",
        "NumpadSubtract, 86",
        "NumpadAdd, 87",
        "NumpadEnter, 88",
        "Numpad1, 89",
        "Numpad5, 93",
        "Numpad9, 97",
        "Numpad0, 98",
        "NumpadDecimal, 99",
        "IntlBackslash, 100",
        "ContextMenu, 101",
        "NumpadEqual, 103",
        "F13, 104",
        "F20, 111",
        "F24, 115",
        "AudioVolumeMute, 127",
        "AudioVolumeUp, 128",
        "AudioVolumeDown, 129",
        "NumpadComma, 133",
        "IntlRo, 135",
        "KanaMode, 136",
        "IntlYen, 137",
        "Convert, 138",
        "NonConvert, 139",
        "Lang1, 144",
        "Lang2, 145",
        "ControlLeft, 224",
        "ShiftLeft, 225",
        "AltLeft, 226",
        "MetaLeft, 227",
        "OSLeft, 227",
        "ControlRight, 228",
        "ShiftRight, 229",
        "AltRight, 230",
        "MetaRight, 231",
        "OSRight, 231",
        "Lang3, 146",
        "Lang4, 147",
        "Lang5, 148",
        "Katakana, 146",
        "Hiragana, 147",
        "Power, 102",
        "Open, 116",
        "Help, 117",
        "Select, 119",
        "Again, 121",
        "Undo, 122",
        "Cut, 123",
        "Copy, 124",
        "Paste, 125",
        "Find, 126",
        "NumpadParenLeft, 182",
        "NumpadParenRight, 183",
        "NumpadBackspace, 187",
        "NumpadHash, 204",
        "NumpadStar, 85",
        "NumpadMemoryStore, 208",
        "NumpadMemoryRecall, 209",
        "NumpadMemoryClear, 210",
        "NumpadMemoryAdd, 211",
        "NumpadMemorySubtract, 212",
        "NumpadClear, 216",
        "NumpadClearEntry, 217",
        // SDL3's own numbers for the consumer page (SDL_scancode.h).
        "Sleep, 258",
        "WakeUp, 259",
        "MediaPlay, 262",
        "MediaPause, 263",
        "MediaRecord, 264",
        "MediaFastForward, 265",
        "MediaRewind, 266",
        "MediaTrackNext, 267",
        "MediaTrackPrevious, 268",
        "MediaStop, 269",
        "Eject, 270",
        "MediaPlayPause, 271",
        "MediaSelect, 272",
        "LaunchMediaPlayer, 272",
        "BrowserSearch, 280",
        "BrowserHome, 281",
        "BrowserBack, 282",
        "BrowserForward, 283",
        "BrowserStop, 284",
        "BrowserRefresh, 285",
        "BrowserFavorites, 286",
    })
    void mapsCodesToHidUsages(String code, int scancode) {
        assertThat(DomKeys.scancode(code)).isEqualTo(scancode);
    }

    @ParameterizedTest
    @CsvSource({"''", "Unidentified", "Fn", "F0", "F25", "Fx", "Key", "Keya", "Digit", "NumpadX", "F1a"})
    void unknownCodesAreUnknown(String code) {
        assertThat(DomKeys.scancode(code)).isEqualTo(Scancodes.UNKNOWN);
    }

    @Test
    void lettersAndDigitsAreContiguous() {
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            assertThat(DomKeys.scancode("Key" + letter)).isEqualTo(Scancodes.A + letter - 'A');
        }
        for (int number = 1; number <= 12; number++) {
            assertThat(DomKeys.scancode("F" + number)).isEqualTo(Scancodes.F1 + number - 1);
        }
    }

    /**
     * Every code in the UI Events KeyboardEvent code tables (W3C), plus F13-F24 and the older
     * names browsers still send. Each either has a scancode or is deliberately unknown.
     */
    private static final List<String> ALL_CODES = List.of(
            // Writing system keys
            "Backquote",
            "Backslash",
            "BracketLeft",
            "BracketRight",
            "Comma",
            "Digit0",
            "Digit1",
            "Digit2",
            "Digit3",
            "Digit4",
            "Digit5",
            "Digit6",
            "Digit7",
            "Digit8",
            "Digit9",
            "Equal",
            "IntlBackslash",
            "IntlRo",
            "IntlYen",
            "KeyA",
            "KeyB",
            "KeyC",
            "KeyD",
            "KeyE",
            "KeyF",
            "KeyG",
            "KeyH",
            "KeyI",
            "KeyJ",
            "KeyK",
            "KeyL",
            "KeyM",
            "KeyN",
            "KeyO",
            "KeyP",
            "KeyQ",
            "KeyR",
            "KeyS",
            "KeyT",
            "KeyU",
            "KeyV",
            "KeyW",
            "KeyX",
            "KeyY",
            "KeyZ",
            "Minus",
            "Period",
            "Quote",
            "Semicolon",
            "Slash",
            // Functional keys
            "AltLeft",
            "AltRight",
            "Backspace",
            "CapsLock",
            "ContextMenu",
            "ControlLeft",
            "ControlRight",
            "Enter",
            "MetaLeft",
            "MetaRight",
            "ShiftLeft",
            "ShiftRight",
            "Space",
            "Tab",
            "Convert",
            "KanaMode",
            "Lang1",
            "Lang2",
            "Lang3",
            "Lang4",
            "Lang5",
            "NonConvert",
            // Control pad and arrows
            "Delete",
            "End",
            "Help",
            "Home",
            "Insert",
            "PageDown",
            "PageUp",
            "ArrowDown",
            "ArrowLeft",
            "ArrowRight",
            "ArrowUp",
            // Numpad
            "NumLock",
            "Numpad0",
            "Numpad1",
            "Numpad2",
            "Numpad3",
            "Numpad4",
            "Numpad5",
            "Numpad6",
            "Numpad7",
            "Numpad8",
            "Numpad9",
            "NumpadAdd",
            "NumpadBackspace",
            "NumpadClear",
            "NumpadClearEntry",
            "NumpadComma",
            "NumpadDecimal",
            "NumpadDivide",
            "NumpadEnter",
            "NumpadEqual",
            "NumpadHash",
            "NumpadMemoryAdd",
            "NumpadMemoryClear",
            "NumpadMemoryRecall",
            "NumpadMemoryStore",
            "NumpadMemorySubtract",
            "NumpadMultiply",
            "NumpadParenLeft",
            "NumpadParenRight",
            "NumpadStar",
            "NumpadSubtract",
            // Function keys
            "Escape",
            "F1",
            "F2",
            "F3",
            "F4",
            "F5",
            "F6",
            "F7",
            "F8",
            "F9",
            "F10",
            "F11",
            "F12",
            "F13",
            "F14",
            "F15",
            "F16",
            "F17",
            "F18",
            "F19",
            "F20",
            "F21",
            "F22",
            "F23",
            "F24",
            "Fn",
            "FnLock",
            "PrintScreen",
            "ScrollLock",
            "Pause",
            // Media keys
            "BrowserBack",
            "BrowserFavorites",
            "BrowserForward",
            "BrowserHome",
            "BrowserRefresh",
            "BrowserSearch",
            "BrowserStop",
            "Eject",
            "LaunchApp1",
            "LaunchApp2",
            "LaunchMail",
            "MediaPlayPause",
            "MediaSelect",
            "MediaStop",
            "MediaTrackNext",
            "MediaTrackPrevious",
            "Power",
            "Sleep",
            "AudioVolumeDown",
            "AudioVolumeMute",
            "AudioVolumeUp",
            "WakeUp",
            // Legacy, non-standard and special keys
            "Hyper",
            "Super",
            "Turbo",
            "Abort",
            "Resume",
            "Suspend",
            "Again",
            "Copy",
            "Cut",
            "Find",
            "Open",
            "Paste",
            "Props",
            "Select",
            "Undo",
            "Hiragana",
            "Katakana",
            "Unidentified",
            // Older browsers' names, and Chromium's extra media codes
            "OSLeft",
            "OSRight",
            "VolumeMute",
            "VolumeUp",
            "VolumeDown",
            "MediaPlay",
            "MediaPause",
            "MediaRecord",
            "MediaFastForward",
            "MediaRewind",
            "LaunchMediaPlayer");

    /** Codes SDL3 has no scancode for, so the game can't see them on a desktop either. */
    private static final Set<String> UNMAPPED = Set.of(
            "Fn",
            "FnLock",
            "LaunchApp1",
            "LaunchApp2",
            "LaunchMail",
            "Hyper",
            "Super",
            "Turbo",
            "Abort",
            "Resume",
            "Suspend",
            "Props",
            "Unidentified");

    /** Codes that name the same key as another: older names, and the phone keypad's "*". */
    private static final Set<String> ALIASES = Set.of(
            "OSLeft",
            "OSRight",
            "VolumeMute",
            "VolumeUp",
            "VolumeDown",
            "Katakana",
            "Hiragana",
            "NumpadStar",
            "LaunchMediaPlayer");

    @Test
    void everyStandardCodeIsMappedOrDeliberatelyUnknown() {
        Map<Integer, String> seen = new HashMap<>();
        for (String code : ALL_CODES) {
            int scancode = DomKeys.scancode(code);
            if (UNMAPPED.contains(code)) {
                assertThat(scancode).as(code).isEqualTo(Scancodes.UNKNOWN);
                continue;
            }
            assertThat(scancode).as(code).isBetween(1, Scancodes.COUNT - 1);
            if (!ALIASES.contains(code)) {
                String other = seen.put(scancode, code);
                assertThat(other).as(code + " has the scancode of " + other).isNull();
            }
        }
    }

    @Test
    void layoutKeysHaveUsNamesToFallBackOn() {
        assertThat(DomKeys.usName(Scancodes.A)).isEqualTo("a");
        assertThat(DomKeys.usName(Scancodes.Z)).isEqualTo("z");
        assertThat(DomKeys.usName(Scancodes.DIGIT_1)).isEqualTo("1");
        assertThat(DomKeys.usName(Scancodes.DIGIT_0)).isEqualTo("0");
        assertThat(DomKeys.usName(DomKeys.scancode("Semicolon"))).isEqualTo(";");
        assertThat(DomKeys.usName(DomKeys.scancode("Slash"))).isEqualTo("/");
        assertThat(DomKeys.usName(DomKeys.scancode("Minus"))).isEqualTo("-");
        // Keys the game names itself.
        assertThat(DomKeys.usName(Scancodes.SPACE)).isNull();
        assertThat(DomKeys.usName(Scancodes.F1)).isNull();
        assertThat(DomKeys.usName(50)).isNull();
        assertThat(DomKeys.usName(DomKeys.scancode("Numpad1"))).isNull();
    }

    @Test
    void aliasesNameTheKeyTheirOriginalDoes() {
        assertThat(DomKeys.scancode("OSLeft")).isEqualTo(DomKeys.scancode("MetaLeft"));
        assertThat(DomKeys.scancode("VolumeUp")).isEqualTo(DomKeys.scancode("AudioVolumeUp"));
        assertThat(DomKeys.scancode("Katakana")).isEqualTo(DomKeys.scancode("Lang3"));
        assertThat(DomKeys.scancode("NumpadStar")).isEqualTo(DomKeys.scancode("NumpadMultiply"));
        assertThat(DomKeys.scancode("LaunchMediaPlayer")).isEqualTo(DomKeys.scancode("MediaSelect"));
    }

    private static int keyCode(String code, String key) {
        return keyCode(code, key, false, new int[Scancodes.COUNT]);
    }

    private static int keyCode(String code, String key, boolean shifted, int[] learned) {
        return DomKeys.keyCode(code, DomKeys.scancode(code), key, shifted, learned);
    }

    @Test
    void printableKeysGiveTheirCharacterInLowerCase() {
        assertThat(keyCode("KeyA", "a")).isEqualTo('a');
        assertThat(keyCode("KeyA", "A")).isEqualTo('a');
        assertThat(keyCode("Space", " ")).isEqualTo(' ');
        assertThat(keyCode("Minus", "-")).isEqualTo('-');
        // AZERTY: the key right of Tab types "a".
        assertThat(keyCode("KeyQ", "a")).isEqualTo('a');
        // German: the key right of L types "ö".
        assertThat(keyCode("Semicolon", "ö")).isEqualTo('ö');
    }

    /** The keycodes the game's screens compare against (EditBox, TextFieldHelper, ChatScreen). */
    @Test
    void namedKeysGiveSdlKeycodes() {
        assertThat(keyCode("Enter", "Enter")).isEqualTo(13);
        assertThat(keyCode("Escape", "Escape")).isEqualTo(27);
        assertThat(keyCode("Backspace", "Backspace")).isEqualTo(8);
        assertThat(keyCode("Tab", "Tab")).isEqualTo(9);
        assertThat(keyCode("Delete", "Delete")).isEqualTo(127);
        assertThat(keyCode("Home", "Home")).isEqualTo(1073741898);
        assertThat(keyCode("End", "End")).isEqualTo(1073741901);
        assertThat(keyCode("F5", "F5")).isEqualTo(1073741886);
        assertThat(keyCode("ControlLeft", "Control")).isEqualTo(1073742048);
        assertThat(keyCode("ControlRight", "Control")).isEqualTo(1073742052);
        assertThat(keyCode("ArrowUp", "ArrowUp")).isEqualTo(82 | NP);
        assertThat(keyCode("Unidentified", "Unidentified")).isEqualTo(NP);
    }

    @Test
    void keypadKeysHaveTheirOwnKeycodes() {
        assertThat(keyCode("Numpad1", "1")).isEqualTo(89 | NP);
        assertThat(keyCode("NumpadAdd", "+")).isEqualTo(87 | NP);
        // The game accepts either Enter (AddFriendWidget), and SDL's is the keypad's own.
        assertThat(keyCode("NumpadEnter", "Enter")).isEqualTo(1073741912);
    }

    @Test
    void keypadWithNumLockOffGivesNavigationKeys() {
        assertThat(keyCode("Numpad1", "End")).isEqualTo(77 | NP);
        assertThat(keyCode("Numpad8", "ArrowUp")).isEqualTo(82 | NP);
        assertThat(keyCode("NumpadDecimal", "Delete")).isEqualTo(127);
        assertThat(keyCode("Numpad5", "Clear")).isEqualTo(156 | NP);
    }

    @Test
    void digitKeysAlwaysGiveDigits() {
        assertThat(keyCode("Digit1", "1")).isEqualTo('1');
        // Shift+1 on US, and unshifted on French AZERTY.
        assertThat(keyCode("Digit1", "!")).isEqualTo('1');
        assertThat(keyCode("Digit1", "&")).isEqualTo('1');
        assertThat(keyCode("Digit0", "à")).isEqualTo('0');
    }

    @Test
    void nonLatinLettersGiveUsLetters() {
        // Russian: the key where US has C types "с", and Ctrl+C must still copy.
        assertThat(keyCode("KeyC", "с")).isEqualTo('c');
        // Greek.
        assertThat(keyCode("KeyV", "ω")).isEqualTo('v');
    }

    @Test
    void shiftedKeysGiveTheUnshiftedCharacterSeenBefore() {
        int[] learned = new int[Scancodes.COUNT];
        // Nothing seen yet: a US layout stands in.
        assertThat(keyCode("Minus", "_", true, learned)).isEqualTo('-');
        // German: this key types "ß" and, shifted, "?".
        assertThat(keyCode("Minus", "ß", false, learned)).isEqualTo('ß');
        assertThat(keyCode("Minus", "?", true, learned)).isEqualTo('ß');
    }

    @Test
    void altGrLettersGiveTheLetter() {
        // German AltGr+Q types "@"; the key is still Q.
        assertThat(keyCode("KeyQ", "@", true, new int[Scancodes.COUNT])).isEqualTo('q');
    }

    @Test
    void letterKeysTypingSymbolsKeepThem() {
        // Dvorak: the key where US has Q types an apostrophe.
        int[] learned = new int[Scancodes.COUNT];
        assertThat(keyCode("KeyQ", "'", false, learned)).isEqualTo('\'');
        assertThat(keyCode("KeyQ", "\"", true, learned)).isEqualTo('\'');
    }

    @Test
    void singleCodePointHandlesSurrogatePairs() {
        assertThat(DomKeys.singleCodePoint("a")).isEqualTo('a');
        assertThat(DomKeys.singleCodePoint("😀")).isEqualTo(0x1F600);
        assertThat(DomKeys.singleCodePoint("Enter")).isEqualTo(-1);
        assertThat(DomKeys.singleCodePoint("")).isEqualTo(-1);
    }
}
