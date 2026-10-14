package incari.client.platform;

import incari.platform.api.Platform;
import net.minecraft.util.TimeSource;
import org.jspecify.annotations.Nullable;

/** Starting and stopping the platform, at the points where the game used to start and stop SDL. */
public final class PlatformLifecycle {

    private static @Nullable NativeLibraryLoader nativeLibraries;

    private PlatformLifecycle() {}

    /**
     * Sets what loads the native libraries at startup; the desktop entry point sets the game's own
     * loader. Nothing is loaded otherwise, and a platform without native code (the browser) never
     * sets it, which also keeps the loader out of that build.
     */
    public static void setNativeLibraryLoader(NativeLibraryLoader loader) {
        nativeLibraries = loader;
    }

    /** Loads the native libraries ({@code Main.main}), before anything else touches them. */
    public static void loadNativeLibraries() throws Exception {
        NativeLibraryLoader loader = nativeLibraries;
        if (loader != null) {
            loader.load();
        }
    }

    /**
     * Whether the platform has native libraries (LWJGL's) at all. Code that reaches into them by
     * reflection, such as {@code DebugMemoryUntracker}, runs only then.
     */
    public static boolean hasNativeLibraries() {
        return nativeLibraries != null;
    }

    /** Loads native libraries; {@code Main} reports anything it throws as a startup crash. */
    @FunctionalInterface
    public interface NativeLibraryLoader {
        void load() throws Exception;
    }

    /**
     * Starts the platform ({@code RenderSystem.initBackendSystem}), before the window exists.
     *
     * @return the platform's monotonic clock, which the game uses for all its timing
     */
    public static TimeSource.NanoTimeSource start() {
        Platform platform = ClientPlatform.get();
        platform.initialize(ClientPlatform.appInfo());
        return platform::nanoTime;
    }

    /**
     * Stops the platform, after the window is closed ({@code Minecraft.close}), and Via if a join
     * started it: its scheduler's threads would keep the desktop's JVM running.
     */
    public static void stop() {
        // The modules' last change may still be waiting out its second.
        incari.client.modules.IncariModules.saveNow();
        incari.via.IncariVia.shutdown();
        ClientPlatform.get().shutdown();
    }
}
