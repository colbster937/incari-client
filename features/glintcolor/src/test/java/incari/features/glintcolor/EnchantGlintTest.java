package incari.features.glintcolor;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class EnchantGlintTest {

    private final EnchantGlint module = new EnchantGlint();
    private final TestGame game = new TestGame(module);

    @Test
    void colorsTheGlintWhileOn() {
        module.setEnabled(true);
        assertThat(game.glintColor).isEqualTo(0x7FC8FF);
        module.color.set(0xFF8800);
        game.tick();
        assertThat(game.glintColor).isEqualTo(0xFF8800);
        module.setEnabled(false);
        assertThat(game.glintColor).isEqualTo(-1);
    }
}
