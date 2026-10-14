package incari.platform.desktop;

import incari.platform.api.AppInfo;
import incari.platform.api.NativeSurfaceFactory;
import incari.platform.api.Platform;
import incari.platform.api.PlatformException;
import incari.platform.api.PlatformLog;
import incari.platform.api.PlatformShell;
import incari.platform.api.audio.PlatformAudio;
import incari.platform.api.fonts.PlatformFonts;
import incari.platform.api.input.PlatformInput;
import incari.platform.api.net.PlatformNetwork;
import incari.platform.api.profile.PlatformProfiling;
import incari.platform.api.rtc.PlatformRtc;
import incari.platform.api.storage.PlatformStorage;
import incari.platform.api.threads.PlatformThreads;
import incari.platform.api.window.PlatformDisplays;
import incari.platform.api.window.PlatformEventListener;
import incari.platform.api.window.PlatformWindow;
import incari.platform.api.window.WindowSpec;
import incari.platform.desktop.audio.OpenAlAudio;
import incari.platform.desktop.net.DesktopNetwork;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLPlatform;
import org.lwjgl.sdl.SDLTimer;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

/**
 * The platform on a normal JVM: SDL3 for the window and input, OpenAL for sound, TCP sockets for
 * servers, the JVM's own threads. This is the desktop dev runtime: the same game code the
 * browser runs, debuggable with ordinary Java tools.
 */
public final class DesktopPlatform implements Platform {

    private PlatformRtc rtc = PlatformRtc.UNSUPPORTED;

    private final PlatformFonts fonts = new FreeTypeFonts();
    private final PlatformStorage storage;
    private final PlatformLog log;
    private final SdlDisplays displays;
    private final SdlInput input = new SdlInput();
    private final SdlShell shell;
    private final SdlEventPump events = new SdlEventPump();
    private final DesktopNetwork network;
    private final PlatformThreads threads = suggested -> suggested;
    private final PlatformProfiling profiling = new DesktopProfiling();
    private final boolean debugLogging;
    private @Nullable OpenAlAudio audio;
    private @Nullable SdlWindow window;

    /**
     * @param debugLogging whether to pass SDL's debug-level messages to the log
     */
    public DesktopPlatform(PlatformStorage storage, PlatformLog log, boolean debugLogging) {
        this.storage = storage;
        this.log = log;
        this.debugLogging = debugLogging;
        this.displays = new SdlDisplays(log);
        this.shell = new SdlShell(log);
        this.network = new DesktopNetwork(log);
    }

    @Override
    public String description() {
        String platform = SDLPlatform.SDL_GetPlatform();
        String driver = SDLVideo.SDL_GetCurrentVideoDriver();
        return "SDL on " + (platform == null ? "an unknown system" : platform)
                + (driver == null ? "" : " (" + driver + ")");
    }

    @Override
    public void initialize(AppInfo app) {
        new SdlLogBridge(log).install(debugLogging);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("SDL.app.metadata.name", app.name());
        metadata.put("SDL.app.metadata.version", app.version());
        metadata.put("SDL.app.metadata.identifier", app.identifier());
        metadata.put("SDL.app.metadata.creator", app.creator());
        metadata.put("SDL.app.metadata.url", app.url());
        metadata.put("SDL.app.metadata.type", "game");
        metadata.forEach(SDLInit::SDL_SetAppMetadataProperty);

        Map<String, String> hints = new LinkedHashMap<>();
        // The JVM handles signals itself.
        hints.put("SDL_NO_SIGNAL_HANDLERS", "1");
        // Alt-tabbing out of fullscreen must not minimize the game.
        hints.put("SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS", "0");
        // The game decides when to quit, after saving.
        hints.put("SDL_QUIT_ON_LAST_WINDOW_CLOSE", "0");
        // A click that focuses the window also reaches the game.
        hints.put("SDL_MOUSE_FOCUS_CLICKTHROUGH", "1");
        hints.put("SDL_ENABLE_SCREEN_KEYBOARD", "0");
        // The game draws the composition text itself; the input method draws only its candidates.
        hints.put("SDL_IME_IMPLEMENTED_UI", "composition");
        hints.forEach(SDLHints::SDL_SetHint);

        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) {
            throw new PlatformException("Couldn't start SDL: " + SdlWindow.sdlError());
        }
    }

    @Override
    public void shutdown() {
        OpenAlAudio currentAudio = audio;
        if (currentAudio != null) {
            currentAudio.close();
        }
        events.close();
        SDLInit.SDL_Quit();
    }

    @Override
    public long nanoTime() {
        return SDLTimer.SDL_GetTicksNS();
    }

    @Override
    public PlatformDisplays displays() {
        return displays;
    }

    @Override
    public PlatformWindow createWindow(WindowSpec spec, NativeSurfaceFactory surface) {
        if (window != null) {
            throw new IllegalStateException("The game has one window, and it already exists");
        }
        SdlWindow created = SdlWindow.create(spec, surface, displays, log);
        window = created;
        events.setWindow(created);
        return created;
    }

    @Override
    public void pollEvents(PlatformEventListener listener) {
        events.poll(listener);
    }

    @Override
    public void endFrame() {
        // The swap chain and the game's own frame limiter pace the loop on the desktop.
    }

    @Override
    public void flushInputEvents() {
        events.flushInput();
    }

    @Override
    public PlatformInput input() {
        return input;
    }

    @Override
    public PlatformShell shell() {
        return shell;
    }

    @Override
    public PlatformAudio audio() {
        OpenAlAudio current = audio;
        if (current == null) {
            // Created on first use: OpenAL's native library loads only when the game starts sound.
            current = new OpenAlAudio(log);
            audio = current;
        }
        return current;
    }

    @Override
    public PlatformNetwork network() {
        return network;
    }

    /**
     * The desktop has no WebRTC (ADR 0019); smoke tests may give it a scripted stand-in, to check
     * voice chat's signalling against a real server. Call before the game starts.
     */
    public void setRtc(PlatformRtc standIn) {
        rtc = standIn;
    }

    @Override
    public PlatformRtc rtc() {
        return rtc;
    }

    @Override
    public void crashNatively() {
        // A write through a null pointer: the JVM dies with a native crash report, like a driver bug.
        MemoryUtil.memSet(0L, 0, 1L);
        throw new IllegalStateException("A write to address 0 didn't crash the JVM");
    }

    @Override
    public PlatformStorage storage() {
        return storage;
    }

    @Override
    public PlatformThreads threads() {
        return threads;
    }

    @Override
    public PlatformProfiling profiling() {
        return profiling;
    }

    @Override
    public PlatformFonts fonts() {
        return fonts;
    }
}
