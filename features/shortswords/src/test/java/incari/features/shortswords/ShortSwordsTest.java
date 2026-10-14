package incari.features.shortswords;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class ShortSwordsTest {

    private final ShortSwords module = new ShortSwords();
    private final TestGame game = new TestGame(module);

    @Test
    void shrinksSwordsOnly() {
        module.setEnabled(true);
        assertThat(game.heldSword(true, false).scale()).isEqualTo(0.7F);
        assertThat(game.heldItem(true, false, false).scale()).isEqualTo(1);
        assertThat(game.heldItem(false, true, false).scale()).isEqualTo(1);
    }

    @Test
    void followsTheSizeAndCanLeaveABlockingSwordAlone() {
        module.setEnabled(true);
        module.size.set(50);
        assertThat(game.heldSword(false, false).scale()).isEqualTo(0.5F);
        module.whileBlocking.set(false);
        assertThat(game.heldSword(true, true).scale()).isEqualTo(1);
    }

    @Test
    void offLeavesSwordsAsTheGameDrawsThem() {
        assertThat(game.heldSword(true, false).scale()).isEqualTo(1);
    }
}
