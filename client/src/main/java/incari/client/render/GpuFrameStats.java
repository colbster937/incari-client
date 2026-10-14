package incari.client.render;

import org.jspecify.annotations.Nullable;

/**
 * What a graphics backend counts of the frame it last presented. The WebGL2 backend
 * installs one; Mojang's desktop backends don't, and the profiler shows their GPU figures as
 * unknown.
 */
public interface GpuFrameStats {

    /**
     * How long the GPU took for a recent frame, in milliseconds, or {@code NaN} where the backend
     * can't time the GPU. A frame or two behind: GPU timers answer late.
     */
    double gpuMillis();

    /** Draw calls in the last frame; a multi-draw counts once. */
    int drawCalls();

    /** Of those, the ones that drew terrain. */
    int terrainDrawCalls();

    /** Terrain sections the last frame drew, one per layer of each section. */
    int terrainSections();

    /** The installed backend's counts, or null. */
    static @Nullable GpuFrameStats current() {
        return Holder.current;
    }

    /** Called by a backend when it's created. */
    static void install(GpuFrameStats stats) {
        Holder.current = stats;
    }

    /** Where the installed stats live: an interface can't have a mutable static field of its own. */
    final class Holder {
        private static volatile @Nullable GpuFrameStats current;

        private Holder() {}
    }
}
