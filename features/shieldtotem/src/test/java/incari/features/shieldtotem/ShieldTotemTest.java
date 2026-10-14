package incari.features.shieldtotem;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.game.EquipmentSlot;
import incari.framework.setting.BooleanSetting;
import incari.framework.testing.FakePlayer;
import incari.framework.testing.TestGame;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShieldTotemTest {

    private final ShieldTotem module = new ShieldTotem();
    private final TestGame game = new TestGame(module);

    @Test
    void showsTheHeldShieldsWearAndTheTotems() {
        module.setEnabled(true);
        FakePlayer player = game.join();
        player.equip(EquipmentSlot.OFFHAND, ShieldTotem.SHIELD, 300, 336, 0).carry(ShieldTotem.TOTEM, 3);
        List<String> drawn = game.drawHud().drawn;
        assertThat(drawn).anyMatch(line -> line.startsWith("item \"minecraft:shield\""));
        assertThat(drawn).anyMatch(line -> line.startsWith("text \"10%\"") && line.endsWith("NEGATIVE"));
        assertThat(drawn).anyMatch(line -> line.startsWith("item \"minecraft:totem_of_undying\""));
        assertThat(drawn).anyMatch(line -> line.startsWith("text \"x3\"") && line.endsWith(" TEXT"));
    }

    @Test
    void saysWhenAnAxeDisabledTheShield() {
        module.setEnabled(true);
        game.join().equip(EquipmentSlot.MAINHAND, ShieldTotem.SHIELD, 0, 336, 0.5F);
        List<String> drawn = game.drawHud().drawn;
        assertThat(drawn).anyMatch(line -> line.startsWith("text \"Disabled\""));
        assertThat(drawn).anyMatch(line -> line.startsWith("fill 24x1") && line.endsWith("NEGATIVE"));
    }

    @Test
    void noShieldNoShieldElement() {
        module.setEnabled(true);
        game.join();
        List<String> drawn = game.drawHud().drawn;
        assertThat(drawn).noneMatch(line -> line.contains("shield"));
        assertThat(drawn).anyMatch(line -> line.startsWith("text \"x0\"") && line.endsWith("NEGATIVE"));

        ((BooleanSetting) module.settings().get("show-no-totems")).set(false);
        assertThat(game.drawHud().drawn).isEmpty();
    }

    @Test
    void theEditorShowsSamples() {
        module.setEnabled(true);
        List<String> drawn = game.drawHud(true).drawn;
        assertThat(drawn).anyMatch(line -> line.startsWith("item \"minecraft:shield\""));
        assertThat(drawn).anyMatch(line -> line.startsWith("text \"x2\""));
    }
}
