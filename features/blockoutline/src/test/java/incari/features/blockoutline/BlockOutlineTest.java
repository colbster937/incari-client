package incari.features.blockoutline;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class BlockOutlineTest {

    private final BlockOutline module = new BlockOutline();
    private final TestGame game = new TestGame(module);

    @Test
    void outlinesInThePlayersColorWhileOnAndTheGamesAfter() {
        module.setEnabled(true);
        assertThat(game.outlineColor).isEqualTo(0xE6A9DCFF);
        assertThat(game.outlineWidth).isEqualTo(2.5F);
        assertThat(game.outlineFill).isZero();

        module.fill.set(true);
        module.color.set(0xFF0000);
        game.tick();
        assertThat(game.outlineColor & 0xFFFFFF).isEqualTo(0xFF0000);
        assertThat(game.outlineFill).isEqualTo(0x33A9DCFF);

        module.setEnabled(false);
        assertThat(game.outlineColor).isZero();
    }

    @Test
    void anOpacityNeverClearsTheColor() {
        assertThat(BlockOutline.withOpacity(0xFF123456, 0) >>> 24).isEqualTo(1);
        assertThat(BlockOutline.withOpacity(0x123456, 100)).isEqualTo(0xFF123456);
    }
}
