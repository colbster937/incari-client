package incari.platform.desktop;

import incari.platform.api.PlatformLog;
import incari.platform.api.window.DisplayInfo;
import incari.platform.api.window.DisplayMode;
import incari.platform.api.window.PlatformDisplays;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_PixelFormatDetails;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

/** Monitors as SDL3 reports them, queried fresh on every call. */
final class SdlDisplays implements PlatformDisplays {

    private final PlatformLog log;

    SdlDisplays(PlatformLog log) {
        this.log = log;
    }

    @Override
    public List<DisplayInfo> all() {
        IntBuffer ids = SDLVideo.SDL_GetDisplays();
        if (ids == null) {
            log.warn("Couldn't list displays: " + SdlWindow.sdlError());
            return List.of();
        }
        List<DisplayInfo> displays = new ArrayList<>();
        try {
            int primary = primaryId();
            for (int i = 0; i < ids.limit(); i++) {
                DisplayInfo display = get(ids.get(i));
                if (display != null) {
                    // Primary first, as the interface promises.
                    displays.add(display.id() == primary ? 0 : displays.size(), display);
                }
            }
        } finally {
            SDLStdinc.SDL_free(ids);
        }
        return displays;
    }

    @Override
    public @Nullable DisplayInfo get(int id) {
        if (id == 0) {
            return null;
        }
        String name = Objects.requireNonNullElse(SDLVideo.SDL_GetDisplayName(id), "unknown display");
        SDL_DisplayMode desktop = SDLVideo.SDL_GetDesktopDisplayMode(id);
        if (desktop == null) {
            log.warn("Couldn't query the desktop mode of display " + name + ": " + SdlWindow.sdlError());
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect bounds = SDL_Rect.malloc(stack);
            if (!SDLVideo.SDL_GetDisplayBounds(id, bounds)) {
                log.warn("Couldn't query the bounds of display " + name + ": " + SdlWindow.sdlError());
                return null;
            }
            return new DisplayInfo(
                    id,
                    name,
                    bounds.x(),
                    bounds.y(),
                    bounds.w(),
                    bounds.h(),
                    toDisplayMode(desktop),
                    fullscreenModes(id, name));
        }
    }

    private List<DisplayMode> fullscreenModes(int id, String name) {
        PointerBuffer modes = SDLVideo.SDL_GetFullscreenDisplayModes(id);
        if (modes == null) {
            log.warn("Couldn't list the video modes of display " + name + ": " + SdlWindow.sdlError());
            return List.of();
        }
        try {
            List<DisplayMode> result = new ArrayList<>(modes.limit());
            for (int i = 0; i < modes.limit(); i++) {
                result.add(toDisplayMode(SDL_DisplayMode.create(modes.get(i))));
            }
            return result;
        } finally {
            SDLStdinc.SDL_free(modes);
        }
    }

    @Override
    public int primaryId() {
        return SDLVideo.SDL_GetPrimaryDisplay();
    }

    static DisplayMode toDisplayMode(SDL_DisplayMode mode) {
        SDL_PixelFormatDetails format = SDLPixels.SDL_GetPixelFormatDetails(mode.format());
        // An unknown pixel format is reported as 8 bits per channel: the only kind modern
        // displays drive, and the kind the game accepts.
        int red = format == null ? 8 : format.Rbits();
        int green = format == null ? 8 : format.Gbits();
        int blue = format == null ? 8 : format.Bbits();
        return new DisplayMode(mode.w(), mode.h(), red, green, blue, mode.refresh_rate());
    }
}
