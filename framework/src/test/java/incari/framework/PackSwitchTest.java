package incari.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

/** A module that's the switch for a resource pack ({@link Module#followPack}). */
class PackSwitchTest {

    static final class Switch extends Module {

        Switch(String pack) {
            super("switch", "Switch", "Turns a pack on.", Category.VISUAL);
            followPack(pack);
        }
    }

    @Test
    void turningTheModuleOnAndOffTurnsThePack() {
        Switch module = new Switch("programmer_art");
        TestGame game = new TestGame(module);

        module.setEnabled(true);
        assertThat(game.selectedPacks).containsExactly("programmer_art");
        assertThat(game.reloads).isEqualTo(1);
        game.ticks(3);
        assertThat(module.isEnabled()).isTrue();

        module.setEnabled(false);
        assertThat(game.selectedPacks).isEmpty();
        assertThat(game.reloads).isEqualTo(2);
    }

    @Test
    void theResourcePacksScreenTurnsTheModule() {
        Switch module = new Switch("programmer_art");
        TestGame game = new TestGame(module);

        game.selectedPacks.add("programmer_art");
        game.tick();
        assertThat(module.isEnabled()).isTrue();

        game.selectedPacks.clear();
        game.tick();
        assertThat(module.isEnabled()).isFalse();
        // Following the game, the module asked for no reload of its own.
        assertThat(game.reloads).isZero();
    }

    @Test
    void atStartThePackWinsOverTheProfile() {
        Switch module = new Switch("programmer_art");
        TestGame game = new TestGame(module);
        module.setEnabled(true);
        game.system().flush();

        // A new start: the profile has the module on, but the player turned the pack off since.
        Switch again = new Switch("programmer_art");
        TestGame restarted = new TestGame(again);
        restarted.files.putAll(game.files);
        restarted.system().load();
        assertThat(again.isEnabled()).isFalse();
        assertThat(restarted.reloads).isZero();

        restarted.selectedPacks.add("programmer_art");
        restarted.system().load();
        assertThat(again.isEnabled()).isTrue();
        assertThat(restarted.reloads).isZero();
    }

    @Test
    void aMissingPackTurnsTheModuleBackOff() {
        Switch module = new Switch("programmer_art");
        TestGame game = new TestGame(module);
        game.packs.clear();

        module.setEnabled(true);
        assertThat(game.warnings).anyMatch(w -> w.contains("programmer_art"));
        game.tick();
        assertThat(module.isEnabled()).isFalse();
    }

    @Test
    void aModuleFollowsOnePack() {
        final class Two extends Module {
            Two() {
                super("two", "Two", "", Category.VISUAL);
                followPack("programmer_art");
                followPack("high_contrast");
            }
        }
        assertThatThrownBy(Two::new).isInstanceOf(IllegalArgumentException.class);
    }
}
