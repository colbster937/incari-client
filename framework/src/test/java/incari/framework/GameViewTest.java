package incari.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import incari.framework.config.Profiles;
import incari.framework.event.Events;
import incari.framework.game.GameOption;
import incari.framework.game.GamePart;
import incari.framework.hud.Anchor;
import incari.framework.hud.GamePartElement;
import incari.framework.setting.FloatSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

/** Option holds, game parts and the fire overlay: what ADR 0050 adds to modules. */
class GameViewTest {

    static final class Holder extends Module {

        final FloatSetting level = settings().decimal("level", "Level", 0, 1, 0.05F, 0.2F);
        int enables;

        Holder() {
            super("holder", "Holder", "Holds the damage tilt.", Category.VISUAL);
            holdOption(GameOption.DAMAGE_TILT, level::get);
        }

        @Override
        protected void onEnable() {
            enables++;
        }
    }

    @Test
    void anOptionIsTakenOnFollowsTheSettingAndIsGivenBack() {
        Holder holder = new Holder();
        TestGame game = new TestGame(holder);
        game.setOption(GameOption.DAMAGE_TILT, 0.9);

        holder.setEnabled(true);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
        holder.level.set(0.5F);
        game.tick();
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.5);
        assertThat(holder.isEnabled()).isTrue();

        holder.setEnabled(false);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.9, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void thePlayersSliderWins() {
        Holder holder = new Holder();
        TestGame game = new TestGame(holder);
        holder.setEnabled(true);
        game.setOption(GameOption.DAMAGE_TILT, 0.7);
        game.tick();
        assertThat(holder.isEnabled()).isFalse();
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.7);

        // Turned on again, it takes the option again and remembers 0.7.
        holder.setEnabled(true);
        holder.setEnabled(false);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.7, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void aPlayersValueEqualToTheModulesIsGivenBack() {
        Holder holder = new Holder();
        TestGame game = new TestGame(holder);
        game.setOption(GameOption.DAMAGE_TILT, 0.2);

        holder.setEnabled(true);
        holder.setEnabled(false);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.2, offset(1e-4));
    }

    @Test
    void thePlayersValueIsGivenBackAfterARestart() {
        Holder first = new Holder();
        TestGame before = new TestGame(first);
        before.setOption(GameOption.DAMAGE_TILT, 0.9);
        first.setEnabled(true);
        before.ticks(2);
        before.system().flush();

        // The game saved its options with the module's value in them.
        Holder second = new Holder();
        TestGame after = new TestGame(second);
        after.files.putAll(before.files);
        after.setOption(GameOption.DAMAGE_TILT, 0.2);
        after.system().load();
        assertThat(second.isEnabled()).isTrue();
        second.setEnabled(false);
        assertThat(after.option(GameOption.DAMAGE_TILT)).isEqualTo(0.9, offset(1e-4));
    }

    @Test
    void anotherProfilesCopyDoesntReplaceThePlayersValue() {
        Holder holder = new Holder();
        TestGame game = new TestGame(holder);
        assertThat(game.system().createProfile("Other")).isTrue();
        game.setOption(GameOption.DAMAGE_TILT, 0.9);
        holder.setEnabled(true);
        game.tick();

        // Default has the module off, and its own (default) idea of the player's value.
        assertThat(game.system().switchProfile(Profiles.DEFAULT)).isTrue();
        assertThat(holder.isEnabled()).isFalse();
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.9, offset(1e-4));
    }

    @Test
    void resetAllKeepsThePlayersValue() {
        Holder holder = new Holder();
        TestGame game = new TestGame(holder);
        game.setOption(GameOption.DAMAGE_TILT, 0.9);
        holder.setEnabled(true);
        game.tick();

        holder.settings().resetAll();
        game.tick();
        holder.setEnabled(false);
        assertThat(game.option(GameOption.DAMAGE_TILT)).isEqualTo(0.9, offset(1e-4));
    }

    @Test
    void anOptionIsHeldOnce() {
        final class Twice extends Module {
            Twice() {
                super("twice", "Twice", "", Category.VISUAL);
                holdOption(GameOption.DAMAGE_TILT, () -> 0);
                holdOption(GameOption.DAMAGE_TILT, () -> 1);
            }
        }
        assertThatThrownBy(Twice::new).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGamePartIsHeldWhileItsModuleIsOn() {
        final class Part extends Module {
            Part() {
                super("part", "Part", "", Category.HUD);
                hud(new GamePartElement(GamePart.SCOREBOARD, "sidebar", Anchor.RIGHT, 1, 0));
            }
        }
        Part part = new Part();
        TestGame game = new TestGame(part);
        assertThat(game.system().holds(GamePart.SCOREBOARD)).isFalse();
        part.setEnabled(true);
        assertThat(game.system().holds(GamePart.SCOREBOARD)).isTrue();
        part.setEnabled(false);
        assertThat(game.system().holds(GamePart.SCOREBOARD)).isFalse();
    }

    @Test
    void theFireOverlayEventCostsNothingUnheard() {
        Holder holder = new Holder();
        TestGame game = new TestGame(holder);
        assertThat(game.system().events().count(Events.FIRE_OVERLAY)).isZero();
        assertThat(game.fireOverlay()).isZero();
    }
}
