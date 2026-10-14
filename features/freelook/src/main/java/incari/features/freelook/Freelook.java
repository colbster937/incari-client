package incari.features.freelook;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.CameraAngles;
import incari.framework.event.Events;
import incari.framework.event.Turn;
import incari.framework.game.Game;
import incari.framework.game.Perspective;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;

/**
 * Freelook: while its key is held (or after a press, as a toggle), the mouse turns the camera round
 * the player instead of the player, so they can look behind them while running on. Letting go
 * snaps the camera back to where the player faces.
 */
public final class Freelook extends Module {

    /** Left Alt's scancode. */
    static final int LEFT_ALT = 226;

    /** How the key works. */
    public enum Mode {
        HOLD("Hold"),
        TOGGLE("Toggle");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** Where the camera goes while looking. */
    public enum View {
        BACK("Behind"),
        FRONT("In front"),
        FIRST("First person");

        private final String label;

        View(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    final KeySetting key = settings()
            .key("look", "Freelook key", Key.keyboard(LEFT_ALT))
            .describedAs("Hold (or press) to look around without turning.");
    final ChoiceSetting<Mode> mode = settings()
            .choice("mode", "Key", Mode.values(), Mode.HOLD, Mode::label)
            .describedAs("Hold the key to look around, or press it once to start and again to stop.");
    final ChoiceSetting<View> view = settings()
            .choice("view", "Camera", View.values(), View.BACK, View::label)
            .describedAs("Where the camera goes while you look around.");
    final BooleanSetting invert = settings()
            .bool("invert", "Invert up and down", false)
            .describedAs("Moving the mouse up looks down while looking around.");

    private boolean toggled;
    private boolean active;
    /** Whether the camera's angles are still to be taken from the player's on the next frame. */
    private boolean fresh;

    private float yaw;
    private float pitch;
    private Perspective before = Perspective.FIRST_PERSON;

    /** Freelook, off until the player turns it on. */
    public Freelook() {
        super("freelook", "Freelook", "Hold a key to look around without turning.", Category.VISUAL);
        key.onPress(() -> toggled = !toggled);
        on(Events.TICK, this::tick);
        on(Events.TURN, this::onTurn);
        on(Events.CAMERA, this::onCamera);
    }

    /** Whether the camera's turning on its own now. */
    boolean looking() {
        return active;
    }

    private void tick() {
        boolean want = system().game().player() != null && (mode.get() == Mode.HOLD ? key.isDown() : toggled);
        if (want != active) {
            if (want) {
                start();
            } else {
                stop();
            }
        }
    }

    private void start() {
        Game game = system().game();
        active = true;
        fresh = true;
        before = game.perspective();
        Perspective wanted =
                switch (view.get()) {
                    case BACK -> Perspective.THIRD_PERSON_BACK;
                    case FRONT -> Perspective.THIRD_PERSON_FRONT;
                    case FIRST -> Perspective.FIRST_PERSON;
                };
        if (wanted != before) {
            game.setPerspective(wanted);
        }
    }

    private void stop() {
        active = false;
        toggled = false;
        Game game = system().game();
        if (game.perspective() != before) {
            game.setPerspective(before);
        }
    }

    @Override
    protected void onDisable() {
        if (active) {
            stop();
        }
        toggled = false;
    }

    private void onTurn(Turn turn) {
        if (!active) {
            return;
        }
        yaw += turn.yaw();
        float down = invert.get() ? -turn.pitch() : turn.pitch();
        pitch = Math.max(-90, Math.min(90, pitch + down));
        turn.consume();
    }

    private void onCamera(CameraAngles camera) {
        if (!active) {
            return;
        }
        if (fresh) {
            yaw = camera.yaw();
            pitch = camera.pitch();
            fresh = false;
        }
        camera.set(yaw, pitch);
    }
}
