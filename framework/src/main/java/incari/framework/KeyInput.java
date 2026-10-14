package incari.framework;

import incari.framework.setting.KeySetting;

/**
 * The game's side of keybinds: what each {@link KeySetting}'s key did since the last tick. The
 * client answers through the game's own key handling, so binds follow the game's rules (nothing
 * fires with a screen open, keys are released when one opens).
 */
public interface KeyInput {

    /** How many times the setting's key went down since the last call; resets the count. */
    int presses(KeySetting key);

    /** Whether the setting's key is held now. */
    boolean isDown(KeySetting key);
}
