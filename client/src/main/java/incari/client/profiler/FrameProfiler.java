package incari.client.profiler;

import incari.client.platform.ClientPlatform;
import java.util.function.Supplier;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

/**
 * Times a frame from the zones the game already marks for its own profiler: the game loop
 * pushes {@code tick}, {@code frame} and, inside {@code frame}, {@code extract}, {@code render},
 * {@code submit} and the rest, so timing those needs no hooks of its own. Only the two outer levels
 * are timed; deeper zones just count their depth.
 *
 * <p>Render thread only, and a hot path while profiling: the game pushes thousands of zones a frame.
 * Nothing here allocates, and a zone's name is only looked at on the two outer levels.
 */
final class FrameProfiler implements ProfilerFiller {

    /** What a zone counts towards. */
    private static final int NONE = 0;

    private static final int TICK = 1;
    private static final int FRAME = 2;
    private static final int EXTRACT = 3;
    private static final int RENDER = 4;

    private final Profiling owner;
    private int depth;
    private int outer = NONE;
    private int inner = NONE;
    private long outerStart;
    private long innerStart;
    private long tickNanos;
    private long extractNanos;
    private long renderNanos;

    FrameProfiler(Profiling owner) {
        this.owner = owner;
    }

    @Override
    public void startTick() {
        depth = 0;
        outer = NONE;
        inner = NONE;
        tickNanos = 0;
        extractNanos = 0;
        renderNanos = 0;
    }

    @Override
    public void endTick() {
        owner.frameEnded(millis(tickNanos), millis(extractNanos), millis(renderNanos));
    }

    @Override
    public void push(String name) {
        depth++;
        if (depth == 1) {
            outer = outerZone(name);
            if (outer != NONE) {
                outerStart = now();
            }
        } else if (depth == 2 && outer == FRAME) {
            inner = innerZone(name);
            if (inner != NONE) {
                innerStart = now();
            }
        }
    }

    @Override
    public void push(Supplier<String> name) {
        if (depth < 2) {
            push(name.get());
        } else {
            depth++;
        }
    }

    @Override
    public void pop() {
        if (depth == 2 && outer == FRAME && inner != NONE) {
            long took = now() - innerStart;
            if (inner == EXTRACT) {
                extractNanos += took;
            } else {
                renderNanos += took;
            }
            inner = NONE;
        } else if (depth == 1 && outer == TICK) {
            tickNanos += now() - outerStart;
            outer = NONE;
        } else if (depth == 1) {
            outer = NONE;
        }
        depth = Math.max(0, depth - 1);
    }

    @Override
    public void popPush(String name) {
        pop();
        push(name);
    }

    @Override
    public void popPush(Supplier<String> name) {
        pop();
        push(name);
    }

    @Override
    public void markForCharting(MetricCategory category) {}

    @Override
    public void incrementCounter(String name, int amount) {}

    @Override
    public void incrementCounter(Supplier<String> name, int amount) {}

    /** The game loop's outer zones ({@code Minecraft.runTick}). */
    static int outerZone(String name) {
        return switch (name) {
            case "scheduledPacketProcessing", "scheduledExecutables", "tick" -> TICK;
            case "frame" -> FRAME;
            default -> NONE;
        };
    }

    /**
     * The zones inside {@code frame} ({@code Minecraft.renderFrame}): preparing the frame, and
     * handing it to the backend, which in the browser is where every WebGL call is made.
     */
    static int innerZone(String name) {
        return switch (name) {
            case "update", "extract" -> EXTRACT;
            case "gpuAsync", "render", "swapchainBlit", "submit", "present" -> RENDER;
            default -> NONE;
        };
    }

    private static long now() {
        return ClientPlatform.get().nanoTime();
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
