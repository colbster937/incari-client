package incari.features.lowfire;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.setting.IntSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class LowFireTest {

    private final LowFire module = new LowFire();
    private final TestGame game = new TestGame(module);

    @Test
    void leavesTheFlamesAloneWhileOff() {
        assertThat(game.fireOverlay()).isZero();
    }

    @Test
    void lowersTheFlamesByTheChosenShare() {
        module.setEnabled(true);
        assertThat(game.fireOverlay()).isEqualTo(0.1F);

        ((IntSetting) module.settings().get("lower")).set(20);
        assertThat(game.fireOverlay()).isEqualTo(0.2F);
    }

    @Test
    void theShareIsWrittenAsAPercentage() {
        IntSetting lower = (IntSetting) module.settings().get("lower");
        assertThat(lower.format(lower.get())).isEqualTo("10%");
    }
}
