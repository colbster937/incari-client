package incari.client.modules;

import incari.framework.ModuleSystem;
import incari.framework.event.CameraAngles;
import incari.framework.event.Events;
import incari.framework.event.Turn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * The turn and camera events ({@link Events#TURN}, {@link Events#CAMERA}): the mouse's turns,
 * which a module may keep from the player, and the camera's angles each frame, which a module may
 * change without turning the player (Freelook). Game and render thread (one thread).
 */
public final class ModuleCamera {

    /** The game's degrees per unit of mouse turn ({@code Entity.turn}). */
    private static final float DEGREES = 0.15F;

    /** One object each for every turn and frame: both events are hot. */
    private static final Turn TURN = new Turn();

    private static final CameraAngles ANGLES = new CameraAngles();

    private ModuleCamera() {}

    /**
     * Hook ({@code MouseHandler.turnPlayer}, before the player turns): the turn handed to modules.
     *
     * @return whether a module took it, so the player doesn't turn
     */
    public static boolean turn(double x, double y) {
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.TURN) == 0) {
            return false;
        }
        Options options = Minecraft.getInstance().options;
        double yaw = options.invertMouseX().get() ? -x : x;
        double pitch = options.invertMouseY().get() ? -y : y;
        TURN.reset((float) yaw * DEGREES, (float) pitch * DEGREES);
        system.events().fireTurn(TURN);
        return TURN.consumed();
    }

    /**
     * Hook ({@code Camera.alignWithEntity}, with the player's view): the camera's angles handed to
     * modules.
     *
     * @return whether a module turned the camera: {@link #yaw()} and {@link #pitch()} then say where
     */
    public static boolean camera(float yaw, float pitch) {
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.CAMERA) == 0) {
            return false;
        }
        ANGLES.reset(yaw, pitch);
        system.events().fireCamera(ANGLES);
        return ANGLES.changed();
    }

    /** The camera's yaw a module chose, after {@link #camera} said one did. */
    public static float yaw() {
        return ANGLES.yaw();
    }

    /** The camera's pitch a module chose. */
    public static float pitch() {
        return ANGLES.pitch();
    }
}
