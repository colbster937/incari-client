package incari.client.platform;

import incari.platform.api.AppInfo;
import incari.platform.api.Platform;
import incari.platform.api.Platforms;
import incari.platform.api.window.PlatformWindow;
import net.minecraft.SharedConstants;
import org.jspecify.annotations.Nullable;

/**
 * The game's side of the platform: the installed {@link Platform} and the one window the game
 * made on it. Mojang's platform classes (replaced in {@code patches/classes.txt}) and the
 * hooks in vendor patches go through here.
 */
public final class ClientPlatform {

    private static volatile @Nullable PlatformWindow window;

    private ClientPlatform() {}

    public static Platform get() {
        return Platforms.current();
    }

    /**
     * Hook ({@code ThreadingDetector}): whether the game's threads all run on one thread, so its
     * checks for two of them writing a chunk at once can never fire and are skipped (ADR 0030:
     * a semaphore and a lock around every palette write, Lithium's {@code chunk.no_locking}).
     */
    public static boolean oneThread() {
        // The JVM tests bootstrap the game with no platform; the game's checks hold there.
        return Platforms.isInstalled() && get().threads().oneThread();
    }

    /** What the platform is told about the game: names for the window manager and mixer. */
    public static AppInfo appInfo() {
        return new AppInfo(
                "Minecraft",
                SharedConstants.getCurrentVersion().name(),
                "com.mojang.minecraft",
                "Mojang Studios",
                "https://www.minecraft.net");
    }

    /** Called by the game's {@code Window} once the platform window exists. */
    public static void windowCreated(PlatformWindow created) {
        window = created;
    }

    /** Called by the game's {@code Window} when it's closed. */
    public static void windowClosed() {
        window = null;
    }

    /** The game's window, or null before it's created and after it's closed. */
    public static @Nullable PlatformWindow window() {
        return window;
    }
}
