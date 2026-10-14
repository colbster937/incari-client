package incari.features.oldtextures;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class OldTexturesTest {

    private final OldTextures module = new OldTextures();
    private final TestGame game = new TestGame(module);

    @Test
    void turnsProgrammerArtOnAndOff() {
        module.setEnabled(true);
        assertThat(game.selectedPacks).containsExactly("programmer_art");
        module.setEnabled(false);
        assertThat(game.selectedPacks).isEmpty();
    }

    @Test
    void followsProgrammerArtInTheResourcePacksScreen() {
        game.selectedPacks.add("programmer_art");
        game.tick();
        assertThat(module.isEnabled()).isTrue();
        game.selectedPacks.remove("programmer_art");
        game.tick();
        assertThat(module.isEnabled()).isFalse();
    }

    @Test
    void leavesHighContrastAlone() {
        game.selectedPacks.add("high_contrast");
        game.tick();
        assertThat(module.isEnabled()).isFalse();
        module.setEnabled(true);
        assertThat(game.selectedPacks).containsExactlyInAnyOrder("high_contrast", "programmer_art");
    }
}
