package incari.platform.api;

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

/**
 * Everything the game needs from the machine it runs on. One implementation per platform:
 * {@code platform-desktop} (LWJGL, for development) and {@code platform-web} (the browser).
 *
 * <p>The game reaches the platform only through this interface and the ones it returns, never
 * through a platform library directly. The running platform is installed once at startup with
 * {@link Platforms#install} and read with {@link Platforms#current}.
 *
 * <p><b>Threading.</b> Unless a method says otherwise, call it from the thread that called
 * {@link #initialize}: the game's render thread. In the browser that's the page's only thread,
 * so no method here blocks.
 */
public interface Platform {

    /** A short description for logs and crash reports, for example "SDL 3.4 on linux (wayland)". */
    String description();

    /**
     * Which build this is, for the usage counts (ADR 0062): {@code fast}, {@code compatible} or
     * {@code html} in the browser, {@code desktop} here by default.
     */
    default String buildKind() {
        return "desktop";
    }

    /**
     * Starts the platform: native libraries, the windowing and input systems, application
     * metadata. Called once, before any other method but {@link #description}.
     *
     * @throws PlatformException if the platform can't run here (no display, missing libraries)
     */
    void initialize(AppInfo app);

    /** Stops the platform. The window must be closed first. Nothing may be called after this. */
    void shutdown();

    /**
     * A monotonic clock in nanoseconds, from an arbitrary origin. The game's frame timing is
     * built on it, so it must be cheap: it's read several times per frame.
     */
    long nanoTime();

    /** The monitors, and notifications when they change. */
    PlatformDisplays displays();

    /**
     * Creates the game's one window.
     *
     * @param surface creates the native surface the graphics backend draws to. The desktop
     *     calls it to make the window itself (the OpenGL and Vulkan backends each need their
     *     own window flags); the browser, whose surface is a canvas, may ignore it.
     * @throws PlatformException if no window can be created
     */
    PlatformWindow createWindow(WindowSpec spec, NativeSurfaceFactory surface);

    /**
     * Delivers every pending window and input event to {@code listener}, then returns. Called
     * once per frame. Events are delivered on the calling thread, in the order they happened.
     */
    void pollEvents(PlatformEventListener listener);

    /**
     * Called once per frame, after the frame is presented. The browser suspends the game here
     * until the next animation frame, which is when the page gets to handle input and draw; the
     * desktop, whose swap already paces the loop, returns at once.
     */
    void endFrame();

    /**
     * Asks the platform to hold the game to at most {@code framesPerSecond} frames a second (the
     * game's frame limit, or its throttling in menus and when idle) by delaying the next frames at
     * {@link #endFrame}. Returns false if it doesn't pace frames itself, and the game should sleep
     * the rest of the frame out as it does on the desktop. The browser paces frames by skipping
     * animation frames: a sleep there would hold the page's frame, not just the game's.
     */
    default boolean limitFrameRate(int framesPerSecond) {
        return false;
    }

    /**
     * Discards pending input events (keys, text, mouse). Used after a long stall, so input
     * queued during a load isn't replayed into the game.
     */
    void flushInputEvents();

    /** Keyboard and mouse state, and key names in the user's keyboard layout. */
    PlatformInput input();

    /** The clipboard, opening links and folders, and native message boxes. */
    PlatformShell shell();

    /** Sound output. */
    PlatformAudio audio();

    /** Connections to game servers. */
    PlatformNetwork network();

    /**
     * WebRTC: voice chat and shared worlds. Only the browser has it; the default says there
     * is none.
     */
    default PlatformRtc rtc() {
        return PlatformRtc.UNSUPPORTED;
    }

    /**
     * Crashes the process the way a native bug would, for testing crash handling (the game's
     * "debug crash" key). Never returns. A platform that can't crash natively throws an error.
     */
    void crashNatively();

    /** Where the game keeps its files. */
    PlatformStorage storage();

    /** Worker pools for background work. */
    PlatformThreads threads();

    /** Screen recording (ADR 0062). The default records nothing: only the browser has it. */
    default PlatformRecorder recorder() {
        return PlatformRecorder.NONE;
    }

    /** Smooth fonts' glyphs. The default draws none: the game keeps its bitmap fonts. */
    default PlatformFonts fonts() {
        return PlatformFonts.UNSUPPORTED;
    }

    /**
     * Memory figures and a place for the game's frame measurements. The default measures
     * nothing and keeps nothing.
     */
    default PlatformProfiling profiling() {
        return PlatformProfiling.NONE;
    }
}
