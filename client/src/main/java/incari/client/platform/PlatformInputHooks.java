package incari.client.platform;

import com.mojang.blaze3d.platform.Window;
import org.jspecify.annotations.Nullable;

/** What Mojang's input code asks the machine, answered by the platform (called from vendor patches). */
public final class PlatformInputHooks {

    private PlatformInputHooks() {}

    /** {@code InputConstants.isKeyDown}: whether the key with this scancode is held. */
    public static boolean isKeyDown(int scancode) {
        return ClientPlatform.get().input().isKeyDown(scancode);
    }

    /** The modifier keys held, as SDL-style bits ({@code KeyModifiers}). */
    public static int modifiers() {
        return ClientPlatform.get().input().modifiers();
    }

    /**
     * {@code InputConstants.grabMouse} and {@code releaseMouse}: puts the cursor where the game
     * expects it, then locks it for looking around, or frees it for menus.
     */
    public static void setMouseGrabbed(Window window, double x, double y, boolean grabbed) {
        window.platformWindow().warpMouse(x, y);
        window.platformWindow().setRelativeMouseMode(grabbed);
    }

    /** A key's label in the player's keyboard layout, or null if the platform has none. */
    public static @Nullable String keyName(int scancode) {
        return ClientPlatform.get().input().keyName(scancode);
    }

    /**
     * {@code MouseHandler.resyncMousePosition}: the cursor position in window coordinates, even
     * when it's outside the window.
     */
    public static double[] cursorPosition(Window window) {
        var position = window.platformWindow().cursorPosition();
        return new double[] {position.x(), position.y()};
    }
}
