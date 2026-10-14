package incari.features.clearchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import incari.framework.game.GameOption;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class ClearChatTest {

    private final ClearChat module = new ClearChat();
    private final TestGame game = new TestGame(module);

    @Test
    void setsTheChatsBackgroundOpacityToZeroWhileOn() {
        game.setOption(GameOption.TEXT_BACKGROUND_OPACITY, 0.7);
        module.setEnabled(true);
        assertThat(game.option(GameOption.TEXT_BACKGROUND_OPACITY)).isZero();
        game.ticks(5);
        assertThat(module.isEnabled()).isTrue();

        module.setEnabled(false);
        assertThat(game.option(GameOption.TEXT_BACKGROUND_OPACITY)).isEqualTo(0.7, offset(1e-4));
    }

    @Test
    void raisingTheSliderInChatSettingsTurnsItOffAndKeepsTheNewValue() {
        module.setEnabled(true);
        game.setOption(GameOption.TEXT_BACKGROUND_OPACITY, 0.3);
        game.tick();
        assertThat(module.isEnabled()).isFalse();
        assertThat(game.option(GameOption.TEXT_BACKGROUND_OPACITY)).isEqualTo(0.3);
    }

    @Test
    void givesThePlayersOpacityBackAfterARestartWithItOn() {
        game.setOption(GameOption.TEXT_BACKGROUND_OPACITY, 0.6);
        module.setEnabled(true);
        game.system().flush();

        // A new start: the game's options kept the 0, the profile has the module on.
        ClearChat again = new ClearChat();
        TestGame restarted = new TestGame(again);
        restarted.files.putAll(game.files);
        restarted.setOption(GameOption.TEXT_BACKGROUND_OPACITY, 0);
        restarted.system().load();
        assertThat(again.isEnabled()).isTrue();

        again.setEnabled(false);
        assertThat(restarted.option(GameOption.TEXT_BACKGROUND_OPACITY)).isEqualTo(0.6, offset(1e-4));
    }

    @Test
    void theHeldValueIsntInTheSettingsScreen() {
        assertThat(module.settings().anyShown()).isTrue(); // the toggle key
        assertThat(module.settings().get("before-text-background-opacity").isVisible())
                .isFalse();
    }
}
