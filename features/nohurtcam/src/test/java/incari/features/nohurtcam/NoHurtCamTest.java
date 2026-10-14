package incari.features.nohurtcam;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.GameOption;
import incari.framework.setting.IntSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class NoHurtCamTest {

    private final NoHurtCam module = new NoHurtCam();
    private final TestGame game = new TestGame(module);

    @Test
    void turnsTheGamesDamageTiltOffAndBack() {
        game.options.put(GameOption.DAMAGE_TILT, 0.8);
        module.setEnabled(true);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isZero();

        module.setEnabled(false);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.8, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void someShakeCanBeKept() {
        module.setEnabled(true);
        ((IntSetting) module.settings().get("shake")).set(25);
        game.tick();
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.25);
        assertThat(module.isEnabled()).isTrue();
    }

    @Test
    void movingTheGamesSliderTurnsItOff() {
        module.setEnabled(true);
        game.setOption(GameOption.DAMAGE_TILT, 0.5);
        game.tick();
        assertThat(module.isEnabled()).isFalse();
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.5);
    }
}
