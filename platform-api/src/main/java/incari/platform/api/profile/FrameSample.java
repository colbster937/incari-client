package incari.platform.api.profile;

/**
 * One frame, measured by the game. Times are milliseconds of the render thread's CPU time
 * spent in each part of the frame, or {@code NaN} where nothing measured it.
 *
 * @param tickMillis the client's ticks and the packets and tasks queued for them
 * @param extractMillis preparing the frame: updating the camera and HUD, extracting render state
 * @param renderMillis issuing the frame to the graphics backend ("render submit")
 * @param gpuMillis how long the GPU took for a recent frame, where the backend can time it; a
 *     frame or two behind, since GPU timers answer late
 * @param drawCalls draw calls issued to the graphics API this frame
 * @param terrainDrawCalls those of them that drew terrain; a multi-draw counts once
 * @param terrainSections terrain sections drawn (each layer of each section once)
 * @param meshQueue sections waiting to be meshed
 */
public record FrameSample(
        double tickMillis,
        double extractMillis,
        double renderMillis,
        double gpuMillis,
        int drawCalls,
        int terrainDrawCalls,
        int terrainSections,
        int meshQueue) {}
