package incari.platform.api.window;

import java.util.List;

/**
 * One monitor.
 *
 * @param id the platform's id for it; stable while it stays connected, never 0
 * @param name a human-readable name, for example "DELL U2720Q"
 * @param x the left edge on the virtual desktop, in screen coordinates
 * @param y the top edge on the virtual desktop
 * @param width the width in screen coordinates
 * @param height the height in screen coordinates
 * @param desktopMode the mode the desktop runs at
 * @param fullscreenModes every mode exclusive fullscreen can switch to, best first; may be empty
 */
public record DisplayInfo(
        int id,
        String name,
        int x,
        int y,
        int width,
        int height,
        DisplayMode desktopMode,
        List<DisplayMode> fullscreenModes) {

    public DisplayInfo {
        fullscreenModes = List.copyOf(fullscreenModes);
    }
}
