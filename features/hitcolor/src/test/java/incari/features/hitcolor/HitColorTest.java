package incari.features.hitcolor;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class HitColorTest {

    private final HitColor module = new HitColor();
    private final TestGame game = new TestGame(module);

    @Test
    void tintsInThePlayersColorWhileOn() {
        module.setEnabled(true);
        assertThat(game.hitColor).isEqualTo(0x5AB8FF);
        assertThat(game.hitStrength).isEqualTo(30);
        module.strength.set(100);
        game.tick();
        assertThat(game.hitStrength).isEqualTo(100);
        // Black is a color like any other, not "off".
        module.color.set(0);
        game.tick();
        assertThat(game.hitColor).isZero();
        assertThat(game.hitStrength).isEqualTo(100);
        module.setEnabled(false);
        assertThat(game.hitStrength).isZero();
    }
}
