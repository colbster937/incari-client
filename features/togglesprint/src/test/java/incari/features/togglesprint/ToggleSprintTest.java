package incari.features.togglesprint;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.event.MovementInput;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.Setting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class ToggleSprintTest {

    private final ToggleSprint module = new ToggleSprint();
    private final TestGame game = new TestGame(module);

    @Test
    void doesNothingWhileOff() {
        assertThat(game.movement(false, 1).sprint()).isFalse();
        assertThat(game.drawHud().drawn).isEmpty();
    }

    @Test
    void aPressLatchesSprintingUntilTheNext() {
        module.setEnabled(true);

        assertThat(game.movement(false, 1).sprint()).isTrue();
        assertThat(game.movement(false, 0).sprint()).isTrue();
        assertThat(module.status()).isEqualTo(ToggleSprint.SPRINTING_TOGGLED);
        assertThat(game.movement(false, 1).sprint()).isFalse();
        assertThat(module.status()).isNull();
        // Two taps within one tick cancel out.
        assertThat(game.movement(false, 2).sprint()).isFalse();
    }

    @Test
    void theGamesOwnToggleIsLeftToTheGame() {
        module.setEnabled(true);
        game.gameTogglesSprint = true;

        // The game's latch on: the module doesn't latch a second time.
        MovementInput input = game.movement(true, 1);
        assertThat(input.sprint()).isTrue();
        assertThat(input.changed()).isFalse();
        assertThat(module.status()).isEqualTo(ToggleSprint.SPRINTING_TOGGLED);
        // The game reset its latch (a respawn): a press then latches it again, and the next stops.
        assertThat(game.movement(false, 0).sprint()).isFalse();
        assertThat(game.movement(true, 1).sprint()).isTrue();
        assertThat(game.movement(false, 1).sprint()).isFalse();
    }

    @Test
    void deathLetsGoOfTheLatches() {
        module.setEnabled(true);
        game.join();
        assertThat(game.movement(false, 1).sprint()).isTrue();

        game.player.dead(true);
        game.tick();
        game.player.dead(false);
        assertThat(game.movement(false, 0).sprint()).isFalse();
    }

    @Test
    void holdingTheKeyStillSprintsWhenNotLatched() {
        module.setEnabled(true);
        MovementInput input = game.movement(true, 0);
        assertThat(input.sprint()).isTrue();
        assertThat(input.changed()).isFalse();
        assertThat(module.status()).isEqualTo(ToggleSprint.SPRINTING_HELD);
    }

    @Test
    void alwaysSprintsWithoutTheKey() {
        module.setEnabled(true);
        choice("sprint", ToggleSprint.SprintMode.ALWAYS);

        assertThat(game.movement(false, 0).sprint()).isTrue();
        assertThat(module.status()).isEqualTo(ToggleSprint.SPRINTING_ALWAYS);
    }

    @Test
    void holdModeLeavesTheKeyAlone() {
        module.setEnabled(true);
        choice("sprint", ToggleSprint.SprintMode.HOLD);

        MovementInput input = game.movement(false, 1);
        assertThat(input.sprint()).isFalse();
        assertThat(input.changed()).isFalse();
    }

    @Test
    void sneakLatchesWhenSetToToggle() {
        module.setEnabled(true);
        choice("sneak", ToggleSprint.SneakMode.TOGGLE);

        MovementInput input = game.movement(false, 0, false, 1);
        assertThat(input.sneak()).isTrue();
        assertThat(module.status()).isEqualTo(ToggleSprint.SNEAKING_TOGGLED);
        assertThat(game.movement(false, 0, false, 1).sneak()).isFalse();
    }

    @Test
    void leavingTheWorldOrTurningOffUnlatches() {
        module.setEnabled(true);
        game.movement(false, 1);
        game.system().events().fireLeave();
        assertThat(game.movement(false, 0).sprint()).isFalse();

        game.movement(false, 1);
        module.setEnabled(false);
        module.setEnabled(true);
        assertThat(game.movement(false, 0).sprint()).isFalse();
    }

    @Test
    void theHudSaysWhatsLatchedInTheThemeOrTheChosenColor() {
        module.setEnabled(true);
        game.movement(false, 1);

        assertThat(game.drawHud().drawn).containsExactly("text \"[Sprinting (Toggled)]\" at 2,2 TEXT");

        bool("custom-color", true);
        assertThat(game.drawHud().drawn).containsExactly("text \"[Sprinting (Toggled)]\" at 2,2 #ffffffff");

        bool("show-status", false);
        assertThat(game.drawHud().drawn).isEmpty();
    }

    @Test
    void theHudEditorShowsASampleUntilTheStatusIsTurnedOff() {
        module.setEnabled(true);
        assertThat(game.drawHud().drawn).isEmpty();
        assertThat(game.drawHud(true).drawn).containsExactly("text \"[Sprinting (Toggled)]\" at 2,2 TEXT");

        bool("show-status", false);
        assertThat(game.drawHud(true).drawn).isEmpty();
    }

    @Test
    void theColorShowsInTheSettingsOnlyWhenItsUsed() {
        Setting<?> color = setting("color");
        assertThat(color.isVisible()).isFalse();
        bool("custom-color", true);
        assertThat(color.isVisible()).isTrue();
        bool("show-status", false);
        assertThat(color.isVisible()).isFalse();
    }

    private Setting<?> setting(String id) {
        Setting<?> setting = module.settings().get(id);
        assertThat(setting).as(id).isNotNull();
        return setting;
    }

    private void bool(String id, boolean value) {
        ((incari.framework.setting.BooleanSetting) setting(id)).set(value);
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> void choice(String id, E value) {
        ((ChoiceSetting<E>) setting(id)).set(value);
    }
}
