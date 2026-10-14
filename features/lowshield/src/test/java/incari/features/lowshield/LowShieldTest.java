package incari.features.lowshield;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.setting.BooleanSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class LowShieldTest {

    private final LowShield module = new LowShield();
    private final TestGame game = new TestGame(module);

    @Test
    void lowersShieldsOnly() {
        module.setEnabled(true);
        assertThat(game.heldItem(false, true, false).lower()).isEqualTo(35 * LowShield.UNITS_PER_PERCENT);
        assertThat(game.heldItem(true, false, false).lower()).isZero();
    }

    @Test
    void canLeaveARaisedShieldAlone() {
        module.setEnabled(true);
        ((BooleanSetting) module.settings().get("while-blocking")).set(false);
        assertThat(game.heldItem(false, true, true).lower()).isZero();
        assertThat(game.heldItem(false, true, false).lower()).isPositive();
    }
}
