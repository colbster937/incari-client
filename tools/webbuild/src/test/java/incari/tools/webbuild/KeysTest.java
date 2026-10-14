package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.tools.build.CliException;
import org.junit.jupiter.api.Test;

class KeysTest {

    @Test
    void chordsNameModifiersShort() {
        assertThat(Keys.chord("Control+Shift+KeyT")).containsExactly("ControlLeft", "ShiftLeft", "KeyT");
        assertThat(Keys.chord("F3+KeyB")).containsExactly("F3", "KeyB");
        assertThat(Keys.chord("Escape")).containsExactly("Escape");
    }

    @Test
    void unknownKeysAreRefused() {
        assertThatThrownBy(() -> Keys.chord("Ctrl+Banana")).isInstanceOf(CliException.class);
    }

    @Test
    void keysAreWhatAUsKeyboardSends() {
        assertThat(Keys.of("KeyA", false)).isEqualTo(new Keys.Key("a", 'A', "a", 0));
        assertThat(Keys.of("KeyA", true)).isEqualTo(new Keys.Key("A", 'A', "A", 0));
        assertThat(Keys.of("Digit2", true)).isEqualTo(new Keys.Key("@", '2', "@", 0));
        assertThat(Keys.of("F3", false)).isEqualTo(new Keys.Key("F3", 114, "", 0));
        assertThat(Keys.of("F24", false).virtualKeyCode()).isEqualTo(135);
        assertThat(Keys.of("Slash", true).text()).isEqualTo("?");
        assertThat(Keys.of("ShiftRight", false).location()).isEqualTo(2);
    }

    @Test
    void sdlKeysAreHidUsagesAndSdlKeycodes() {
        assertThat(Keys.sdl("KeyE")).isEqualTo(new Keys.SdlKey(8, 'e'));
        assertThat(Keys.sdl("Digit1")).isEqualTo(new Keys.SdlKey(30, '1'));
        assertThat(Keys.sdl("Digit0")).isEqualTo(new Keys.SdlKey(39, '0'));
        assertThat(Keys.sdl("F1")).isEqualTo(new Keys.SdlKey(58, 0x4000003A));
        assertThat(Keys.sdl("F3")).isEqualTo(new Keys.SdlKey(60, 0x4000003C));
        assertThat(Keys.sdl("Escape")).isEqualTo(new Keys.SdlKey(41, 0x1B));
        assertThatThrownBy(() -> Keys.sdl("Banana")).isInstanceOf(CliException.class);
    }

    @Test
    void modifierKeysSetTheirBit() {
        assertThat(Keys.modifierBit("ControlRight")).isEqualTo(Keys.CTRL);
        assertThat(Keys.modifierBit("ShiftLeft")).isEqualTo(Keys.SHIFT);
        assertThat(Keys.modifierBit("KeyA")).isZero();
    }
}
