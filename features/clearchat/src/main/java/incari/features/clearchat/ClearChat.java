package incari.features.clearchat;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.GameOption;

/**
 * Clear Chat: no dark box behind the chat's lines. It holds the game's own Text Background Opacity
 * (Chat Settings) at 0 while on, so the game's slider shows it; moving that slider hands control
 * back to the player and turns the module off. Turning the module off puts the player's opacity
 * back.
 */
public final class ClearChat extends Module {

    /** Clear Chat, off until the player turns it on. */
    public ClearChat() {
        super(
                "clearchat",
                "Clear Chat",
                "Removes the dark box behind chat (the game's Text Background Opacity, set to 0%).",
                Category.HUD);
        holdOption(GameOption.TEXT_BACKGROUND_OPACITY, () -> 0);
    }
}
