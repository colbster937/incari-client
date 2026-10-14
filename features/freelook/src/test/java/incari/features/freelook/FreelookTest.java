package incari.features.freelook;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.event.CameraAngles;
import incari.framework.event.Turn;
import incari.framework.game.Perspective;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.Key;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class FreelookTest {

    private static final Key ALT = Key.keyboard(Freelook.LEFT_ALT);

    private final Freelook module = new Freelook();
    private final TestGame game = new TestGame(module);

    @Test
    void holdingTheKeyTurnsTheCameraNotThePlayer() {
        module.setEnabled(true);
        game.join();
        game.hold(ALT, true);
        game.tick();
        assertThat(game.perspective).isEqualTo(Perspective.THIRD_PERSON_BACK);

        CameraAngles start = game.camera(90, 10);
        assertThat(start.changed()).isTrue();
        Turn turn = game.turn(30, -5);
        assertThat(turn.consumed()).isTrue();
        CameraAngles after = game.camera(90, 10);
        assertThat(after.yaw()).isEqualTo(120);
        assertThat(after.pitch()).isEqualTo(5);

        game.hold(ALT, false);
        game.tick();
        assertThat(game.perspective).isEqualTo(Perspective.FIRST_PERSON);
        assertThat(game.turn(30, 0).consumed()).isFalse();
        assertThat(game.camera(90, 10).changed()).isFalse();
    }

    @Test
    void theToggleStaysOnUntilPressedAgain() {
        module.setEnabled(true);
        @SuppressWarnings("unchecked")
        ChoiceSetting<Freelook.Mode> mode =
                (ChoiceSetting<Freelook.Mode>) module.settings().get("mode");
        mode.set(Freelook.Mode.TOGGLE);
        game.join();
        game.press(ALT);
        game.tick();
        game.tick();
        assertThat(module.looking()).isTrue();
        game.press(ALT);
        game.tick();
        assertThat(module.looking()).isFalse();
    }

    @Test
    void lookingStraightUpIsAsFarAsItGoes() {
        module.setEnabled(true);
        game.join();
        game.hold(ALT, true);
        game.tick();
        game.camera(0, 0);
        game.turn(0, -500);
        assertThat(game.camera(0, 0).pitch()).isEqualTo(-90);
    }

    @Test
    void turningOffPutsTheViewBack() {
        module.setEnabled(true);
        game.join();
        game.hold(ALT, true);
        game.tick();
        module.setEnabled(false);
        assertThat(game.perspective).isEqualTo(Perspective.FIRST_PERSON);
    }
}
