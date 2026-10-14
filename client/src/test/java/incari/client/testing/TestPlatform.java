package incari.client.testing;

import incari.platform.api.Platform;
import incari.platform.api.Platforms;
import incari.platform.api.threads.PlatformThreads;
import java.lang.reflect.Proxy;

/**
 * Enough of a platform for game classes to load in tests: building a {@code VertexFormat} runs
 * {@code Mth}'s static initializer, which reaches {@code Util}'s, which asks the platform how many
 * background threads to make. Anything else the game asks for fails the test loudly.
 */
public final class TestPlatform {

    private TestPlatform() {}

    public static void install() {
        if (Platforms.isInstalled()) {
            return;
        }
        PlatformThreads inline = suggested -> 0;
        Platforms.install((Platform) Proxy.newProxyInstance(
                Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "threads" -> inline;
                    case "description" -> "client tests";
                    case "nanoTime" -> System.nanoTime();
                    case "toString" -> "TestPlatform";
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }
}
