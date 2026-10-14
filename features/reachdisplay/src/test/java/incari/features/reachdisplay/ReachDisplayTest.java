package incari.features.reachdisplay;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.setting.IntSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class ReachDisplayTest {

    private final ReachDisplay module = new ReachDisplay();
    private final TestGame game = new TestGame(module);

    @Test
    void showsTheLastHitForAFewSeconds() {
        module.setEnabled(true);
        assertThat(game.drawHud().drawn).isEmpty();

        game.attack(2.874);
        assertThat(game.drawHud().drawn).anyMatch(line -> line.startsWith("text \"2.87 blocks\""));

        game.millis += 3000;
        assertThat(game.drawHud().drawn).isEmpty();
    }

    @Test
    void neverHidesWhenAsked() {
        module.setEnabled(true);
        ((IntSetting) module.settings().get("hide-after")).set(0);
        game.attack(3.1);
        game.millis += 60_000;
        assertThat(game.drawHud().drawn).anyMatch(line -> line.startsWith("text \"3.10 blocks\""));
    }

    @Test
    void showsASampleInTheEditor() {
        module.setEnabled(true);
        assertThat(game.drawHud(true).drawn).anyMatch(line -> line.startsWith("text \"3.00 blocks\""));
    }

    @Test
    void writesTheChosenDecimals() {
        assertThat(ReachDisplay.text(2.9996, 3)).isEqualTo("3.000 blocks");
        assertThat(ReachDisplay.text(3.05, 1)).isEqualTo("3.1 blocks");
        assertThat(ReachDisplay.text(0.04, 2)).isEqualTo("0.04 blocks");
    }
}
