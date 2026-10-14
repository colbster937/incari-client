package incari.platform.api;

import org.jspecify.annotations.Nullable;

/**
 * Creates the native window a graphics backend draws into. Mojang's backends own this step
 * ({@code GpuBackend.createWindow}) because each needs its own window flags: OpenGL a GL-capable
 * window, Vulkan a Vulkan one. The platform calls it from {@link Platform#createWindow}.
 */
@FunctionalInterface
public interface NativeSurfaceFactory {

    /**
     * Creates the window.
     *
     * @param title the window title, or null for none
     * @param width the width in screen coordinates
     * @param height the height in screen coordinates
     * @param flags platform window flags (on the desktop, SDL window flags) the platform needs
     *     on every window, for example resizable and high-density
     * @return the native window handle, or 0 if creation failed
     */
    long create(@Nullable String title, int width, int height, long flags);
}
