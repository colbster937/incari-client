package incari.features.nohurtcam;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.GameOption;
import incari.framework.setting.IntSetting;

/**
 * No Hurt Cam: the camera doesn't tilt when the player is hurt. It holds the game's own Damage Tilt
 * option (Accessibility Settings) at the module's value while on, so there's nothing to patch and
 * the game's slider shows what's in effect; moving that slider hands control back to the player
 * and turns the module off.
 */
public final class NoHurtCam extends Module {

    private final IntSetting shake = settings()
            .integer("shake", "Shake amount", 0, 100, 0)
            .formattedAs(percent -> percent + "%")
            .describedAs("0%: the camera doesn't move at all when you're hurt.");

    /** No Hurt Cam, off until the player turns it on. */
    public NoHurtCam() {
        super(
                "nohurtcam",
                "No Hurt Cam",
                "Stops the camera shaking when you take damage (the game's Damage Tilt).",
                Category.VISUAL);
        holdOption(GameOption.DAMAGE_TILT, () -> shake.get() / 100.0);
    }
}
