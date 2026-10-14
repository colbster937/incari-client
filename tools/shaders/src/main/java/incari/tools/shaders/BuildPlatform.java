package incari.tools.shaders;

import incari.platform.api.Platform;
import incari.platform.api.Platforms;
import incari.platform.api.threads.PlatformThreads;
import java.lang.reflect.Proxy;

/**
 * The platform the game sees while this tool lists its pipelines. Loading {@code RenderPipelines}
 * runs the game's static initializers, and {@code Util}'s asks the platform how many background
 * threads to make; nothing else is reached. Everything else fails loudly, so a future game version
 * that reaches further at class-load time shows up here rather than half-working.
 */
final class BuildPlatform {

    private BuildPlatform() {}

    static void install() {
        if (Platforms.isInstalled()) {
            return;
        }
        PlatformThreads inline = suggested -> 0;
        Platform platform = (Platform) Proxy.newProxyInstance(
                Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "threads" -> inline;
                    case "description" -> "Build-time shader translation (no game running)";
                    case "nanoTime" -> System.nanoTime();
                    case "toString" -> "BuildPlatform";
                    default ->
                        throw new UnsupportedOperationException("The shader tool's platform has no " + method.getName()
                                + "(); the game reached it while its pipelines were listed");
                });
        Platforms.install(platform);
    }
}
