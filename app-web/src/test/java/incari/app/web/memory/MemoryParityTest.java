package incari.app.web.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import incari.platform.api.Platform;
import incari.platform.api.Platforms;
import incari.platform.api.threads.PlatformThreads;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The memory layer's gate (ADR 0008): the game's vertex and image code writes the same bytes over web-classlib's memory
 * as over LWJGL's. {@link MemoryScenario} runs twice, once as the desktop game runs it (LWJGL's
 * {@code MemoryUtil}, STB's PNG decoder) and once with the game's classes linked to the browser
 * build's stand-ins ({@link SubstitutingClassLoader}), and every result must match to the byte.
 *
 * <p>This covers the stand-ins' logic and the heap's bookkeeping with its JVM backend; the browser
 * probe runs the same scenario over linear memory and prints {@link MemoryScenario#digest}s to compare with these.
 */
class MemoryParityTest {

    /**
     * What the game's static initializers ask of the platform while these classes load: how many
     * background threads {@code Util} may start. Anything else fails loudly.
     */
    @BeforeAll
    static void installPlatform() {
        if (Platforms.isInstalled()) {
            return;
        }
        PlatformThreads inline = suggested -> 0;
        Platforms.install((Platform) Proxy.newProxyInstance(
                Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "threads" -> inline;
                    case "nanoTime" -> System.nanoTime();
                    case "toString" -> "MemoryParityTest platform";
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    @Test
    void vertexAndImageBytesMatchVanilla() throws Exception {
        Map<String, byte[]> vanilla = new MemoryScenario().get();

        SubstitutingClassLoader loader = new SubstitutingClassLoader(
                getClass().getClassLoader(), BufferBuilder.class, Set.of(MemoryScenario.class.getName()));
        @SuppressWarnings("unchecked") // The scenario's type, loaded again.
        Supplier<Map<String, byte[]>> substituted =
                (Supplier<Map<String, byte[]>>) loader.loadClass(MemoryScenario.class.getName())
                        .getConstructor()
                        .newInstance();
        Map<String, byte[]> browser = substituted.get();

        assertThat(browser.keySet()).containsExactlyElementsOf(vanilla.keySet());
        for (Map.Entry<String, byte[]> result : vanilla.entrySet()) {
            assertThat(result.getValue()).as(result.getKey()).isNotEmpty();
            assertThat(browser.get(result.getKey())).as(result.getKey()).isEqualTo(result.getValue());
        }
        System.out.println("Memory parity: " + vanilla.size() + " results match; " + loader.reloadedCount()
                + " game classes linked to the stand-ins");
        vanilla.forEach((name, bytes) -> System.out.println("  " + name + " " + MemoryScenario.digest(bytes)));
    }
}
