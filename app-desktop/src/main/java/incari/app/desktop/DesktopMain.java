package incari.app.desktop;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import incari.app.desktop.smoke.SceneAgent;
import incari.app.desktop.smoke.SmokeTest;
import incari.client.datafix.DataFixing;
import incari.client.platform.GraphicsBackends;
import incari.client.platform.PlatformLifecycle;
import incari.client.profile.PlayerName;
import incari.client.startup.DeferredModels;
import incari.platform.api.PlatformLog;
import incari.platform.api.Platforms;
import incari.platform.desktop.DesktopPlatform;
import incari.platform.desktop.DesktopStorage;
import java.util.List;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the game on the desktop platform: installs {@link DesktopPlatform} and the desktop's
 * graphics backends, then hands over to the game's own {@code Main} with the same arguments a
 * launcher passes.
 */
public final class DesktopMain {

    private DesktopMain() {}

    public static void main(String[] args) {
        List<String> arguments = List.of(args);
        DesktopStorage storage = new DesktopStorage(
                optionValue(arguments, "--gameDir", "."),
                optionValue(arguments, "--assetsDir", "assets"),
                nullableOptionValue(arguments, "--assetIndex"));
        Logger logger = LoggerFactory.getLogger("Platform");
        DesktopPlatform platform = new DesktopPlatform(storage, slf4j(logger), logger.isDebugEnabled());
        SmokeTest.configurePlatform(platform);
        Platforms.install(platform);
        GraphicsBackends.install(DesktopMain::desktopBackends);
        GraphicsBackends.installVulkanCheck(VulkanBackend::checkBackendAvailable);
        PlatformLifecycle.setNativeLibraryLoader(NativeLibrariesBootstrap::loadLibraries);
        if (Boolean.getBoolean("incari.browserStartup")) {
            // The browser build's start-up, to try it here: -Pincari.browserStartup.
            DataFixing.buildLazily();
            DeferredModels.enable();
        }
        if (System.getProperty("incari.smoke") != null
                || System.getProperty("incari.sceneAgent") != null
                || Boolean.getBoolean("incari.fixedName")) {
            // Tests, and -Pincari.username, name the player: no Edit Profile at start-up.
            PlayerName.fixAtLaunch();
        }
        SmokeTest.startIfRequested(storage.gameDirectory());
        SceneAgent.startIfRequested();
        net.minecraft.client.main.Main.main(args);
    }

    /** OpenGL first, as vanilla does, unless the player chose Vulkan; the other is the fallback. */
    private static GpuBackend[] desktopBackends(PreferredGraphicsApi preference) {
        GpuBackend opengl = new GlBackend();
        GpuBackend vulkan = new VulkanBackend();
        return preference == PreferredGraphicsApi.VULKAN
                ? new GpuBackend[] {vulkan, opengl}
                : new GpuBackend[] {opengl, vulkan};
    }

    private static PlatformLog slf4j(Logger logger) {
        return (level, message, error) -> {
            switch (level) {
                case DEBUG -> logger.debug(message, error);
                case INFO -> logger.info(message, error);
                case WARN -> logger.warn(message, error);
                case ERROR -> logger.error(message, error);
            }
        };
    }

    private static String optionValue(List<String> arguments, String option, String fallback) {
        String value = nullableOptionValue(arguments, option);
        return value == null ? fallback : value;
    }

    private static @Nullable String nullableOptionValue(List<String> arguments, String option) {
        int at = arguments.indexOf(option);
        return at >= 0 && at + 1 < arguments.size() ? arguments.get(at + 1) : null;
    }
}
