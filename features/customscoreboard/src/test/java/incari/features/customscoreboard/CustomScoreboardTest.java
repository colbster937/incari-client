package incari.features.customscoreboard;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.GamePart;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudElement;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class CustomScoreboardTest {

    private final CustomScoreboard module = new CustomScoreboard();
    private final TestGame game = new TestGame(module);
    private final HudElement sidebar = module.hudElements().get(0);

    @Test
    void takesTheSidebarFromTheGameOnlyWhileOn() {
        assertThat(game.system().holds(GamePart.SCOREBOARD)).isFalse();
        module.setEnabled(true);
        assertThat(game.system().holds(GamePart.SCOREBOARD)).isTrue();
        module.setEnabled(false);
        assertThat(game.system().holds(GamePart.SCOREBOARD)).isFalse();
    }

    @Test
    void drawsTheSidebarWhereTheGameWouldByDefault() {
        module.setEnabled(true);
        game.parts.put(GamePart.SCOREBOARD, new int[] {80, 60});
        // Right side, a unit in, middle of a 320 by 240 screen.
        assertThat(game.drawHud().drawn).containsExactly("part SCOREBOARD 80x60 at 239,90");
    }

    @Test
    void drawsItWhereThePlayerPutItAtTheirScale() {
        module.setEnabled(true);
        game.parts.put(GamePart.SCOREBOARD, new int[] {80, 60});
        sidebar.anchor().set(Anchor.TOP_LEFT);
        sidebar.offsetX().set(4);
        sidebar.offsetY().set(10);
        sidebar.scale().set(0.5F);
        assertThat(game.drawHud().drawn).containsExactly("part SCOREBOARD 40x30 at 4,10");
    }

    @Test
    void nothingWithoutASidebarButASampleInTheEditor() {
        module.setEnabled(true);
        assertThat(game.drawHud().drawn).isEmpty();
        assertThat(game.drawHud(true).drawn).containsExactly("part SCOREBOARD 60x40 at 259,100");
    }
}
