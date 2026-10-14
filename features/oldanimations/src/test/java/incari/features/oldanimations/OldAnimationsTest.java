package incari.features.oldanimations;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.event.HeldItem;
import incari.framework.setting.BooleanSetting;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class OldAnimationsTest {

    private final OldAnimations module = new OldAnimations();
    private final TestGame game = new TestGame(module);

    @Test
    void asksForEveryOldPoseByDefault() {
        module.setEnabled(true);
        HeldItem item = game.heldItem(true, false, true);
        assertThat(item.swingWhileUsing()).isTrue();
        assertThat(item.legacyItems()).isTrue();
        assertThat(item.legacyRod()).isTrue();
        assertThat(item.legacyBlock()).isTrue();
    }

    @Test
    void eachCanBeTurnedOff() {
        module.setEnabled(true);
        ((BooleanSetting) module.settings().get("block-hit")).set(false);
        ((BooleanSetting) module.settings().get("rod")).set(false);
        HeldItem item = game.heldItem(true, false, false);
        assertThat(item.swingWhileUsing()).isFalse();
        assertThat(item.legacyRod()).isFalse();
        assertThat(item.legacyItems()).isTrue();
    }

    @Test
    void offChangesNothing() {
        HeldItem item = game.heldItem(true, false, false);
        assertThat(item.legacyItems()).isFalse();
    }
}
