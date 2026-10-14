package incari.platform.api.profile;

/**
 * The platform's side of profiling (ADR 0021). The game measures its frames itself and hands
 * each one here; the browser keeps them where the benchmark runner reads them.
 */
public interface PlatformProfiling {

    /** Memory in use now. Cheap enough to read once a frame. */
    MemoryUsage memory();

    /**
     * Records a frame the game just finished, before the platform's {@code endFrame}. Only called
     * while the game's profiler is collecting.
     */
    void frameProfiled(FrameSample sample);

    /**
     * Records that the game reached a point of its start-up ({@code "title"}: the title screen
     * shown; {@code "world"}: the first frame of a world), with the platform's clock.
     */
    void mark(String name);

    /**
     * Records one tick of singleplayer's server where it runs apart from the game's frames (the
     * browser's server worker, ADR 0039), for the singleplayer benchmark to read.
     *
     * @param millis how long the tick took
     * @param paused whether the server was paused (only its connection ticked)
     * @param loadedChunks the overworld's loaded chunks after the tick
     */
    default void serverTickProfiled(double millis, boolean paused, int loadedChunks) {}

    /**
     * Whether a measuring tool (the benchmark runner) has asked the game for {@code name}, such as
     * leaving the world; a request is answered once. Platforms without such a tool never ask.
     */
    default boolean takeRequest(String name) {
        return false;
    }

    /** Profiling on a platform that records nothing and can't see its memory. */
    PlatformProfiling NONE = new PlatformProfiling() {
        @Override
        public MemoryUsage memory() {
            return MemoryUsage.UNKNOWN;
        }

        @Override
        public void frameProfiled(FrameSample sample) {}

        @Override
        public void mark(String name) {}
    };
}
