package incari.features.theme;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.ThemeColors;
import incari.framework.game.UiFont;
import incari.framework.setting.ActionSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class ThemeEditorTest {

    private final ThemeEditor module = new ThemeEditor();
    private final TestGame game = new TestGame(module);

    @Test
    void isAlwaysOnAndStartsAsFrost() {
        game.system().load();
        assertThat(module.isEnabled()).isTrue();
        assertThat(game.theme).isEqualTo(ThemeColors.FROST);
        assertThat(game.font).isEqualTo(UiFont.RED_HAT_DISPLAY);
        module.setEnabled(false);
        assertThat(module.isEnabled()).isTrue();
        assertThat(module.label()).isEqualTo("Frost");
    }

    @Test
    void theFontAppliesOnTheNextTick() {
        game.system().load();
        module.font.set(UiFont.INTER);
        game.tick();
        assertThat(game.font).isEqualTo(UiFont.INTER);
    }

    @Test
    void presetsAndColorsApplyOnTheNextTick() {
        game.system().load();
        module.preset.set(ThemeEditor.Preset.EMBER);
        game.tick();
        assertThat(game.theme).isEqualTo(ThemeEditor.Preset.EMBER.colors());

        ((ColorSetting) module.settings().get("accent")).set(0x123456);
        game.tick();
        assertThat(game.theme).isNotNull().extracting(ThemeColors::accent).isEqualTo(0x123456);
        assertThat(module.preset.get()).isEqualTo(ThemeEditor.Preset.CUSTOM);
    }

    @Test
    void sharesAndPastesACode() {
        game.system().load();
        module.preset.set(ThemeEditor.Preset.MINT);
        module.font.set(UiFont.POPPINS);
        ((ActionSetting) module.settings().get("copy-code")).run();
        assertThat(game.clipboard).isEqualTo("incari-theme:7fe8c3-f4fffa-a7c4b9-18291f-56-2a-4-4");

        assertThat(game.messages).containsExactly("Theme: Theme code copied. Paste it to share your theme.");

        // In the browser the clipboard can't be read on a click: the player pastes into a field.
        String code = game.clipboard;
        module.preset.set(ThemeEditor.Preset.FROST);
        module.font.set(UiFont.RED_HAT_DISPLAY);
        game.clipboard = "";
        game.typed = "Try mine! " + code + " :)";
        ((ActionSetting) module.settings().get("paste-code")).run();
        game.tick();
        assertThat(game.screens).containsExactly("ask:Import theme");
        assertThat(game.theme).isEqualTo(ThemeEditor.Preset.MINT.colors());
        assertThat(game.font).isEqualTo(UiFont.POPPINS);
        assertThat(module.preset.get()).isEqualTo(ThemeEditor.Preset.CUSTOM);
        assertThat(game.messages).last().isEqualTo("Theme: Theme loaded.");
    }

    @Test
    void aReadableClipboardFillsTheFieldAndJunkIsRefused() {
        game.system().load();
        game.clipboard = ThemeEditor.encode(ThemeEditor.Preset.ROSE.colors(), UiFont.INTER);
        game.typed = "";
        ((ActionSetting) module.settings().get("paste-code")).run();
        game.tick();
        assertThat(game.theme).isEqualTo(ThemeEditor.Preset.ROSE.colors());

        game.typed = "hello";
        ((ActionSetting) module.settings().get("paste-code")).run();
        game.tick();
        assertThat(game.theme).isEqualTo(ThemeEditor.Preset.ROSE.colors());
        assertThat(game.messages).last().isEqualTo("Theme: That isn't a theme code.");
    }

    @Test
    void readsCodesFromBeforeFonts() {
        ThemeEditor.Shared shared = ThemeEditor.decode("incari-theme:7fe8c3-f4fffa-a7c4b9-18291f-56-2a-4");
        assertThat(shared).isNotNull();
        assertThat(shared.colors()).isEqualTo(ThemeEditor.Preset.MINT.colors());
        assertThat(shared.font()).isNull();
    }

    @Test
    void ignoresClipboardsWithoutACode() {
        assertThat(ThemeEditor.decode("hello")).isNull();
        assertThat(ThemeEditor.decode("incari-theme:12-34")).isNull();
        assertThat(ThemeEditor.decode(ThemeEditor.encode(ThemeColors.FROST, UiFont.MICHROMA)))
                .isEqualTo(new ThemeEditor.Shared(ThemeColors.FROST, UiFont.MICHROMA));
    }
}
