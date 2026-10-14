package incari.features.cps;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.event.ClickListener;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class CpsTest {

    private final Cps module = new Cps();
    private final TestGame game = new TestGame(module);

    @Test
    void countsEachButtonsClicksOverTheLastSecond() {
        module.setEnabled(true);
        for (int i = 0; i < 9; i++) {
            game.click(ClickListener.LEFT);
            game.millis += 100;
        }
        game.click(ClickListener.RIGHT);
        game.click(ClickListener.MIDDLE);
        assertThat(game.drawHud().drawn).anyMatch(line -> line.startsWith("text \"9 | 1 CPS\""));

        game.millis += 600;
        assertThat(game.drawHud().drawn).anyMatch(line -> line.startsWith("text \"3 | 1 CPS\""));

        game.millis += 1000;
        assertThat(game.drawHud().drawn).anyMatch(line -> line.startsWith("text \"0 | 0 CPS\""));
    }

    @Test
    void clicksWhileOffArentCounted() {
        game.click(ClickListener.LEFT);
        module.setEnabled(true);
        assertThat(game.drawHud().drawn).anyMatch(line -> line.startsWith("text \"0 | 0 CPS\""));
    }

    @Test
    void writesOneButtonWhenAsked() {
        assertThat(Cps.text(Cps.Buttons.LEFT, 12, 4)).isEqualTo("12 CPS");
        assertThat(Cps.text(Cps.Buttons.RIGHT, 12, 4)).isEqualTo("4 CPS");
    }
}
