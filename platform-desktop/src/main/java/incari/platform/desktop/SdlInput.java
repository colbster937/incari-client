package incari.platform.desktop;

import incari.platform.api.input.PlatformInput;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Keyboard state from SDL3. SDL's scancodes are HID usage ids and its modifier bits are the
 * platform-api's, so nothing needs translating.
 */
final class SdlInput implements PlatformInput {

    /** SDL's key state array: owned by SDL, valid for the life of the program, updated on poll. */
    private @Nullable ByteBuffer keyboardState;

    @Override
    public boolean isKeyDown(int scancode) {
        ByteBuffer state = keyboardState;
        if (state == null) {
            state = SDLKeyboard.SDL_GetKeyboardState();
            if (state == null) {
                return false;
            }
            keyboardState = state;
        }
        return scancode >= 0 && scancode < state.limit() && state.get(scancode) != 0;
    }

    @Override
    public int modifiers() {
        // SDL_Keymod is an unsigned 16-bit value in a Java short.
        return SDLKeyboard.SDL_GetModState() & 0xFFFF;
    }

    @Override
    public @Nullable String keyName(int scancode) {
        int keyCode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false);
        String name = SDLKeyboard.SDL_GetKeyName(keyCode);
        return name == null || name.isEmpty() ? null : name;
    }
}
