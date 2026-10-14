package incari.tools.sprites;

import incari.platform.api.Platform;
import incari.platform.api.Platforms;
import incari.platform.api.threads.PlatformThreads;
import java.lang.reflect.Proxy;

/**
 * The platform the game sees while this tool decodes its sprites: loading the game's classes runs
 * {@code Util}'s static initializer, which asks the platform how many background threads to make.
 * Anything else fails loudly, so a game version that reaches further at class-load time shows up
 * here rather than half-working.
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
                    case "description" -> "Build-time sprite decoding (no game running)";
                    case "nanoTime" -> System.nanoTime();
                    case "toString" -> "BuildPlatform";
                    default ->
                        throw new UnsupportedOperationException("The sprite tool's platform has no " + method.getName()
                                + "(); the game reached it while its sprites were decoded");
                });
        Platforms.install(platform);
    }
}
