package incari.client.platform;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;

/**
 * Which graphics backends the game tries, in order. The entry point installs the choice for its
 * platform: Mojang's OpenGL and Vulkan backends on the desktop, WebGL2 in the browser.
 */
public final class GraphicsBackends {

    private static volatile @Nullable Function<PreferredGraphicsApi, GpuBackend[]> provider;
    private static volatile @Nullable Supplier<@Nullable BackendCreationException> vulkanCheck;
    private static volatile boolean rendering = true;

    private GraphicsBackends() {}

    /** Installs the backends; call before the game starts. */
    public static void install(Function<PreferredGraphicsApi, GpuBackend[]> backends) {
        provider = backends;
    }

    /**
     * Installs the check behind the game's "Vulkan isn't available" notice (the desktop's is
     * {@code VulkanBackend.checkBackendAvailable}). Without one, as in the browser, which has no
     * Vulkan backend to offer, there's nothing to report.
     */
    public static void installVulkanCheck(Supplier<@Nullable BackendCreationException> check) {
        vulkanCheck = check;
    }

    /** {@code Minecraft}'s startup check: why Vulkan can't be used, or null if it can or isn't offered. */
    public static @Nullable BackendCreationException checkVulkanAvailable() {
        Supplier<@Nullable BackendCreationException> check = vulkanCheck;
        return check == null ? null : check.get();
    }

    /**
     * Whether the platform offers Mojang's Vulkan backend: whether it installed a Vulkan check.
     * {@code ClientBootstrap} loads the backend's feature tables only then; they describe LWJGL's
     * Vulkan structs, which the browser build doesn't have.
     */
    public static boolean isVulkanOffered() {
        return vulkanCheck != null;
    }

    /**
     * Switches the frame's render step off or on. With it off the game still ticks and extracts
     * each frame's state, but draws nothing: how a platform without a renderer runs the game.
     */
    public static void setRendering(boolean on) {
        rendering = on;
    }

    /** Whether {@code Minecraft.renderFrame} runs the game renderer this frame. */
    public static boolean isRendering() {
        return rendering;
    }

    /** {@code PreferredGraphicsApi.getBackendsToTry}: the backends to try, most wanted first. */
    public static GpuBackend[] backendsToTry(PreferredGraphicsApi preference) {
        Function<PreferredGraphicsApi, GpuBackend[]> current = provider;
        if (current == null) {
            throw new IllegalStateException(
                    "No graphics backends installed; start the game through app-desktop or app-web");
        }
        return current.apply(preference);
    }
}
