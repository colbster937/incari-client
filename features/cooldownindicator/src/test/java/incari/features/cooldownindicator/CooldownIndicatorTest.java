package incari.features.cooldownindicator;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.GameOption;
import incari.framework.testing.TestGame;
import java.util.List;
import org.junit.jupiter.api.Test;

class CooldownIndicatorTest {

    private final CooldownIndicator module = new CooldownIndicator();
    private final TestGame game = new TestGame(module);

    @Test
    void holdsTheGamesIndicatorOffWhileOn() {
        game.options.put(GameOption.ATTACK_INDICATOR, 2.0);
        module.setEnabled(true);
        assertThat(game.option(GameOption.ATTACK_INDICATOR)).isZero();
        module.setEnabled(false);
        assertThat(game.option(GameOption.ATTACK_INDICATOR)).isEqualTo(2.0);
    }

    @Test
    void drawsTheChargeAsABarBelowTheCrosshair() {
        module.setEnabled(true);
        game.join();
        game.attackStrength = 0.5F;
        List<String> drawn = game.drawLive().drawn;
        assertThat(drawn).containsExactly("fill 24x2 at 148,131 BACKGROUND", "fill 12x2 at 148,131 ACCENT");
    }

    @Test
    void hidesWhenChargedUnlessOnATarget() {
        module.setEnabled(true);
        game.join();
        assertThat(game.drawLive().drawn).isEmpty();
        game.onTarget = true;
        assertThat(game.drawLive().drawn).contains("fill 24x2 at 148,131 POSITIVE");
    }

    @Test
    void ringsTheCrosshair() {
        module.setEnabled(true);
        module.style.set(CooldownIndicator.Style.RING);
        game.join();
        game.attackStrength = 0.25F;
        assertThat(game.drawLive().drawn)
                .containsExactly("ring r10 at 160,120 BACKGROUND", "arc r10 25% at 160,120 ACCENT");
    }

    @Test
    void writesThePercentage() {
        assertThat(CooldownIndicator.percent(0.604F)).isEqualTo("60%");
    }
}
