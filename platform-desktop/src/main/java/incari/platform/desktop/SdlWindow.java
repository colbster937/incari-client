package incari.platform.desktop;

import static org.lwjgl.sdl.SDLError.SDL_GetError;

import incari.platform.api.NativeSurfaceFactory;
import incari.platform.api.PlatformException;
import incari.platform.api.PlatformLog;
import incari.platform.api.window.DisplayInfo;
import incari.platform.api.window.DisplayMode;
import incari.platform.api.window.IconImage;
import incari.platform.api.window.PlatformWindow;
import incari.platform.api.window.SystemBehavior;
import incari.platform.api.window.SystemCursor;
import incari.platform.api.window.WindowSpec;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** The game's window as an SDL3 window. */
final class SdlWindow implements PlatformWindow {

    /** Flags every game window gets, whichever graphics backend creates it. */
    static final long BASE_FLAGS = SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;

    private final long handle;
    private final SdlDisplays displays;
    private final PlatformLog log;
    private final boolean borderlessWindowForFullscreen;
    private final Map<SystemCursor, Long> cursors = new EnumMap<>(SystemCursor.class);
    /**
     * Borderless fullscreen done as a plain window covering the display, one pixel wider so the
     * driver doesn't treat it as exclusive fullscreen and flip the display mode.
     */
    private boolean inBorderlessWindow;

    private boolean hasTextInputArea;
    private int textAreaX;
    private int textAreaY;
    private int textAreaWidth;
    private int textAreaHeight;

    private SdlWindow(long handle, SdlDisplays displays, PlatformLog log) {
        this.handle = handle;
        this.displays = displays;
        this.log = log;
        // Windows' own fullscreen switch is slow and loses the window on alt-tab; a borderless
        // window covering the display behaves like the desktop expects there.
        this.borderlessWindowForFullscreen =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static SdlWindow create(WindowSpec spec, NativeSurfaceFactory surface, SdlDisplays displays, PlatformLog log) {
        long handle = surface.create(spec.title(), spec.width(), spec.height(), BASE_FLAGS);
        if (handle == 0L) {
            throw new PlatformException("Couldn't create the window: " + sdlError());
        }
        SDLVideo.SDL_SetWindowMinimumSize(handle, WindowSpec.MIN_WIDTH, WindowSpec.MIN_HEIGHT);
        SdlWindow window = new SdlWindow(handle, displays, log);
        window.setMaximumSize(spec.maximumSize(), spec.maximumSize());
        log.info("Created window using SDL video driver: " + SDLVideo.SDL_GetCurrentVideoDriver());
        return window;
    }

    @Override
    public long nativeHandle() {
        return handle;
    }

    @Override
    public int x() {
        return position()[0];
    }

    @Override
    public int y() {
        return position()[1];
    }

    private int[] position() {
        int[] known = knownPosition();
        return known == null ? new int[] {0, 0} : known;
    }

    /** The window's position on the desktop, or null where the system doesn't reveal it. */
    private int @Nullable [] knownPosition() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer left = stack.mallocInt(1);
            IntBuffer top = stack.mallocInt(1);
            return SDLVideo.SDL_GetWindowPosition(handle, left, top) ? new int[] {left.get(0), top.get(0)} : null;
        }
    }

    @Override
    public int width() {
        return size()[0];
    }

    @Override
    public int height() {
        return size()[1];
    }

    private int[] size() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            if (!SDLVideo.SDL_GetWindowSize(handle, w, h)) {
                throw new PlatformException("Couldn't query the window size: " + sdlError());
            }
            return new int[] {w.get(0), h.get(0)};
        }
    }

    @Override
    public int framebufferWidth() {
        return Math.max(1, pixelSize()[0]);
    }

    @Override
    public int framebufferHeight() {
        return Math.max(1, pixelSize()[1]);
    }

    @Override
    public int unusedWidth() {
        return inBorderlessWindow ? 1 : 0;
    }

    private int[] pixelSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            if (!SDLVideo.SDL_GetWindowSizeInPixels(handle, w, h)) {
                throw new PlatformException("Couldn't query the window's size in pixels: " + sdlError());
            }
            return new int[] {w.get(0), h.get(0)};
        }
    }

    @Override
    public float pixelDensity() {
        float density = SDLVideo.SDL_GetWindowPixelDensity(handle);
        return density > 0.0F ? density : 1.0F;
    }

    @Override
    public int displayId() {
        return SDLVideo.SDL_GetDisplayForWindow(handle);
    }

    @Override
    public void setTitle(String title) {
        SDLVideo.SDL_SetWindowTitle(handle, title);
    }

    @Override
    public void setIcon(List<IconImage> sizes) {
        if (sizes.isEmpty()) {
            return;
        }
        List<ByteBuffer> pixelBuffers = new ArrayList<>();
        List<SDL_Surface> surfaces = new ArrayList<>();
        try {
            for (IconImage icon : sizes) {
                ByteBuffer pixels = MemoryUtil.memAlloc(icon.rgba().length);
                pixelBuffers.add(pixels);
                pixels.put(icon.rgba()).flip();
                // ABGR8888 is a packed format: on a little-endian machine its bytes are R, G, B, A.
                SDL_Surface surface = SDLSurface.SDL_CreateSurfaceFrom(
                        icon.width(), icon.height(), SDLPixels.SDL_PIXELFORMAT_ABGR8888, pixels, icon.width() * 4);
                if (surface == null) {
                    log.warn("Couldn't make a " + icon.width() + "x" + icon.height() + " icon surface: " + sdlError());
                    continue;
                }
                surfaces.add(surface);
            }
            if (surfaces.isEmpty()) {
                return;
            }
            SDL_Surface primary = surfaces.getFirst();
            for (SDL_Surface alternate : surfaces.subList(1, surfaces.size())) {
                if (!SDLSurface.SDL_AddSurfaceAlternateImage(primary, alternate)) {
                    log.warn("Couldn't add an alternate icon size: " + sdlError());
                }
            }
            if (!SDLVideo.SDL_SetWindowIcon(handle, primary)) {
                log.warn("Couldn't set the window icon: " + sdlError());
            }
        } finally {
            surfaces.forEach(SDLSurface::SDL_DestroySurface);
            pixelBuffers.forEach(MemoryUtil::memFree);
        }
    }

    @Override
    public void setMaximumSize(int width, int height) {
        SDLVideo.SDL_SetWindowMaximumSize(handle, width, height);
    }

    @Override
    public boolean setWindowed(int x, int y, int width, int height) {
        leaveBorderlessWindow();
        if (!SDLVideo.SDL_SetWindowFullscreen(handle, false)) {
            log.warn("Couldn't leave fullscreen: " + sdlError());
            return false;
        }
        boolean placed = place(x, y, width, height);
        finishModeChange();
        return placed;
    }

    @Override
    public boolean setFullscreen(@Nullable DisplayMode exclusiveMode) {
        boolean done;
        if (exclusiveMode != null) {
            leaveBorderlessWindow();
            done = enterExclusive(exclusiveMode);
        } else if (borderlessWindowForFullscreen) {
            done = enterBorderlessWindow();
        } else {
            leaveBorderlessWindow();
            done = enterDesktopFullscreen();
        }
        if (!done) {
            log.warn("Couldn't enter fullscreen: " + sdlError());
        }
        finishModeChange();
        return done;
    }

    private boolean enterExclusive(DisplayMode wanted) {
        int display = displayId();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_DisplayMode closest = SDL_DisplayMode.malloc(stack);
            if (display != 0
                    && SDLVideo.SDL_GetClosestFullscreenDisplayMode(
                            display, wanted.width(), wanted.height(), wanted.refreshRate(), true, closest)) {
                return SDLVideo.SDL_SetWindowFullscreenMode(handle, closest)
                        && SDLVideo.SDL_SetWindowFullscreen(handle, true);
            }
        }
        log.warn("No display mode close to " + wanted.width() + "x" + wanted.height() + "@" + wanted.refreshRate()
                + " for exclusive fullscreen; using borderless fullscreen instead");
        return enterDesktopFullscreen();
    }

    /** SDL's own borderless fullscreen: fullscreen at the desktop's mode. */
    private boolean enterDesktopFullscreen() {
        return SDLVideo.SDL_SetWindowFullscreenMode(handle, null) && SDLVideo.SDL_SetWindowFullscreen(handle, true);
    }

    private boolean enterBorderlessWindow() {
        DisplayInfo display = displays.get(displayId());
        if (display == null) {
            log.warn("Can't find the window's display for borderless fullscreen; using SDL's fullscreen instead");
            leaveBorderlessWindow();
            return enterDesktopFullscreen();
        }
        if (!SDLVideo.SDL_SetWindowFullscreenMode(handle, null) || !SDLVideo.SDL_SetWindowFullscreen(handle, false)) {
            return false;
        }
        if (SDLVideo.SDL_RestoreWindow(handle)) {
            SDLVideo.SDL_SyncWindow(handle);
        }
        inBorderlessWindow = true;
        if (!SDLVideo.SDL_SetWindowBordered(handle, false)) {
            log.warn("Couldn't remove the window border for borderless fullscreen: " + sdlError());
        }
        return place(display.x(), display.y(), display.width() + 1, display.height());
    }

    private void leaveBorderlessWindow() {
        if (inBorderlessWindow) {
            inBorderlessWindow = false;
            if (!SDLVideo.SDL_SetWindowBordered(handle, true)) {
                log.warn("Couldn't restore the window border: " + sdlError());
            }
        }
    }

    private boolean place(int x, int y, int width, int height) {
        if (!SDLVideo.SDL_SetWindowSize(handle, width, height)) {
            return false;
        }
        if (!SDLVideo.SDL_SetWindowPosition(handle, x, y)) {
            // Wayland and some window managers place windows themselves; that's fine.
            log.debug("The window manager declined to position the window: " + sdlError());
        }
        return true;
    }

    private void finishModeChange() {
        if (!SDLVideo.SDL_SyncWindow(handle)) {
            log.debug("Window state didn't settle before the timeout: " + sdlError());
        }
        updateMouseGrab();
    }

    /**
     * Confines the mouse to the window in exclusive fullscreen, where the display belongs to the
     * game; anywhere else the player must be able to leave the window.
     */
    void updateMouseGrab() {
        if (!SDLVideo.SDL_SetWindowMouseGrab(handle, isExclusiveFullscreen())) {
            log.warn("Couldn't update the window's mouse grab: " + sdlError());
        }
    }

    @Override
    public void setSystemBehavior(SystemBehavior behavior, boolean enabled) {
        // Both are macOS features; SDL ignores these hints elsewhere.
        String hint =
                switch (behavior) {
                    case CTRL_CLICK_IS_RIGHT_CLICK -> "SDL_MAC_CTRL_CLICK_EMULATE_RIGHT_CLICK";
                    case MENU_BAR_IN_FULLSCREEN -> "SDL_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY";
                };
        SDLHints.SDL_SetHint(hint, enabled ? "1" : "0");
    }

    @Override
    public boolean isFullscreen() {
        return inBorderlessWindow || (SDLVideo.SDL_GetWindowFlags(handle) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0;
    }

    @Override
    public boolean isExclusiveFullscreen() {
        return !inBorderlessWindow && isFullscreen() && SDLVideo.SDL_GetWindowFullscreenMode(handle) != null;
    }

    @Override
    public @Nullable DisplayMode currentDisplayMode() {
        SDL_DisplayMode mode = SDLVideo.SDL_GetWindowFullscreenMode(handle);
        if (mode == null) {
            int display = displayId();
            mode = display == 0 ? null : SDLVideo.SDL_GetCurrentDisplayMode(display);
        }
        return mode == null ? null : SdlDisplays.toDisplayMode(mode);
    }

    @Override
    public void setRelativeMouseMode(boolean enabled) {
        if (!SDLMouse.SDL_SetWindowRelativeMouseMode(handle, enabled)) {
            log.warn("Couldn't " + (enabled ? "enable" : "disable") + " relative mouse mode: " + sdlError());
        }
    }

    @Override
    public void warpMouse(double x, double y) {
        SDLMouse.SDL_WarpMouseInWindow(handle, (float) x, (float) y);
    }

    @Override
    public CursorPosition cursorPosition() {
        // Window position plus SDL's desktop-wide cursor position gives an answer even while the
        // cursor is outside the window. Without a window position (some Wayland compositors),
        // only the window-relative position is available.
        int[] origin = knownPosition();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer cursorX = stack.mallocFloat(1);
            FloatBuffer cursorY = stack.mallocFloat(1);
            if (origin == null) {
                SDLMouse.SDL_GetMouseState(cursorX, cursorY);
                return new CursorPosition(cursorX.get(0), cursorY.get(0));
            }
            SDLMouse.SDL_GetGlobalMouseState(cursorX, cursorY);
            return new CursorPosition(cursorX.get(0) - origin[0], cursorY.get(0) - origin[1]);
        }
    }

    @Override
    public void setCursor(SystemCursor cursor) {
        long sdlCursor = cursors.computeIfAbsent(cursor, SdlWindow::createCursor);
        SDLMouse.SDL_SetCursor(sdlCursor != 0L ? sdlCursor : SDLMouse.SDL_GetDefaultCursor());
    }

    private static long createCursor(SystemCursor cursor) {
        int shape =
                switch (cursor) {
                    case DEFAULT, ARROW -> SDLMouse.SDL_SYSTEM_CURSOR_DEFAULT;
                    case TEXT -> SDLMouse.SDL_SYSTEM_CURSOR_TEXT;
                    case CROSSHAIR -> SDLMouse.SDL_SYSTEM_CURSOR_CROSSHAIR;
                    case POINTING_HAND -> SDLMouse.SDL_SYSTEM_CURSOR_POINTER;
                    case RESIZE_NS -> SDLMouse.SDL_SYSTEM_CURSOR_NS_RESIZE;
                    case RESIZE_EW -> SDLMouse.SDL_SYSTEM_CURSOR_EW_RESIZE;
                    case RESIZE_ALL -> SDLMouse.SDL_SYSTEM_CURSOR_MOVE;
                    case NOT_ALLOWED -> SDLMouse.SDL_SYSTEM_CURSOR_NOT_ALLOWED;
                };
        return cursor == SystemCursor.DEFAULT
                ? SDLMouse.SDL_GetDefaultCursor()
                : SDLMouse.SDL_CreateSystemCursor(shape);
    }

    @Override
    public void startTextInput() {
        if (hasTextInputArea) {
            applyTextInputArea();
        }
        SDLKeyboard.SDL_StartTextInput(handle);
        SDLKeyboard.SDL_ClearComposition(handle);
    }

    @Override
    public void stopTextInput() {
        SDLKeyboard.SDL_StopTextInput(handle);
    }

    @Override
    public void setTextInputArea(int x, int y, int width, int height) {
        hasTextInputArea = true;
        textAreaX = x;
        textAreaY = y;
        textAreaWidth = width;
        textAreaHeight = height;
        applyTextInputArea();
    }

    private void applyTextInputArea() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect.Buffer area = SDL_Rect.malloc(1, stack);
            area.get(0).x(textAreaX).y(textAreaY).w(textAreaWidth).h(textAreaHeight);
            // -1: no cursor offset; the input method places its window at the area's edge.
            SDLKeyboard.SDL_SetTextInputArea(handle, area, -1);
        }
    }

    @Override
    public void setQuitShortcutEnabled(boolean enabled) {
        SDLHints.SDL_SetHint("SDL_WINDOWS_CLOSE_ON_ALT_F4", enabled ? "1" : "0");
    }

    @Override
    public void close() {
        for (Map.Entry<SystemCursor, Long> cursor : cursors.entrySet()) {
            // The default cursor belongs to SDL; only the ones created here are freed.
            if (cursor.getKey() != SystemCursor.DEFAULT && cursor.getValue() != 0L) {
                SDLMouse.SDL_DestroyCursor(cursor.getValue());
            }
        }
        cursors.clear();
        SDLVideo.SDL_DestroyWindow(handle);
    }

    static String sdlError() {
        String error = SDL_GetError();
        return error == null || error.isEmpty() ? "no error reported" : error;
    }
}
