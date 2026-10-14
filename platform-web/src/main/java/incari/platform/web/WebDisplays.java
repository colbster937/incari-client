package incari.platform.web;

import incari.platform.api.window.DisplayInfo;
import incari.platform.api.window.DisplayMode;
import incari.platform.api.window.PlatformDisplays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The screen the page is on: the only display a page can see. */
final class WebDisplays implements PlatformDisplays {

    static final int SCREEN = 1;

    static DisplayMode screenMode() {
        // Pages can't read the refresh rate; 60 Hz is what almost every Chromebook runs at.
        return new DisplayMode(Dom.screenWidth(), Dom.screenHeight(), 8, 8, 8, 60.0F);
    }

    @Override
    public List<DisplayInfo> all() {
        DisplayInfo screen = get(SCREEN);
        return screen == null ? List.of() : List.of(screen);
    }

    @Override
    public @Nullable DisplayInfo get(int id) {
        if (id != SCREEN) {
            return null;
        }
        DisplayMode mode = screenMode();
        return new DisplayInfo(SCREEN, "Screen", 0, 0, mode.width(), mode.height(), mode, List.of());
    }

    @Override
    public int primaryId() {
        return SCREEN;
    }
}
