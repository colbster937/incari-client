package incari.features.fullbright;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class FullbrightTest {

    private final Fullbright module = new Fullbright();
    private final TestGame game = new TestGame(module);

    @Test
    void lightsTheWorldWhileOnAndGivesTheGamesLightBack() {
        assertThat(game.brightness).isZero();
        module.setEnabled(true);
        assertThat(game.brightness).isEqualTo(1F);

        module.level.set(50);
        game.tick();
        assertThat(game.brightness).isEqualTo(0.5F);

        module.setEnabled(false);
        assertThat(game.brightness).isZero();
    }
}
