package incari.features.hiteffects;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.Effect;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class HitEffectsTest {

    private final HitEffects module = new HitEffects();
    private final TestGame game = new TestGame(module);

    @Test
    void showsAnEffectOnEachHitAndABurstOnAKill() {
        module.setEnabled(true);
        game.system().events().fireAttack(3);
        assertThat(game.effects).containsExactly(Effect.MAGIC);
        assertThat(game.effectCount).isEqualTo(6);
        assertThat(game.effectSound).isFalse();

        game.kill();
        assertThat(game.effects).containsExactly(Effect.MAGIC, Effect.FIREWORK);
        assertThat(game.effectCount).isEqualTo(30);
        assertThat(game.effectSound).isTrue();
    }

    @Test
    void eachCanBeTurnedOff() {
        module.setEnabled(true);
        module.onHit.set(false);
        module.onKill.set(false);
        game.system().events().fireAttack(3);
        game.kill();
        assertThat(game.effects).isEmpty();
    }

    @Test
    void nothingWhileOff() {
        game.system().events().fireAttack(3);
        game.kill();
        assertThat(game.effects).isEmpty();
    }
}
