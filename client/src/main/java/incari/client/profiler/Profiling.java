package incari.client.profiler;

import incari.client.IncariKeys;
import incari.client.platform.ClientPlatform;
import incari.client.render.GpuFrameStats;
import incari.platform.api.profile.FrameSample;
import incari.platform.api.profile.MemoryUsage;
import incari.platform.api.profile.PlatformProfiling;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;

/**
 * The profiler's switch and its state (ADR 0021). It collects while its overlay is shown or
 * while something asked it to ({@link #collect}: the browser page's {@code ?profile}, the desktop's
 * {@code -Dincari.profile=true}); otherwise the game's frames go by untouched. Each frame it
 * collects is handed to the platform ({@link PlatformProfiling#frameProfiled}) and kept for the
 * overlay.
 *
 * <p>Start-up's marks (the title screen, the first frame of a world) are marked with the
 * platform whether or not it collects, for load-time measurements.
 *
 * <p>Render thread only.
 */
public final class Profiling {

    private static final Profiling INSTANCE = new Profiling();

    /** Frames kept for the overlay: its graph, and the 1% low over about 16 seconds at 60 fps. */
    private static final int HISTORY = 1000;

    private final FrameProfiler filler = new FrameProfiler(this);
    private final FrameHistory history = new FrameHistory(HISTORY);
    private boolean requested = Boolean.getBoolean("incari.profile");
    private boolean overlay;
    private boolean reloadedMarked;
    private boolean titleMarked;
    private boolean worldMarked;
    private long lastFrameEnd;
    private @Nullable FrameSample last;
    private MemoryUsage memory = MemoryUsage.UNKNOWN;

    private Profiling() {}

    static Profiling get() {
        return INSTANCE;
    }

    /** Collects frames even while the overlay is hidden (the benchmark runner asks for this). */
    public static void collect(boolean collect) {
        INSTANCE.requested = collect;
    }

    /** Shows or hides the overlay. */
    public static void showOverlay(boolean show) {
        INSTANCE.overlay = show;
    }

    static boolean overlayShown() {
        return INSTANCE.overlay;
    }

    /**
     * Called by the game loop with the profiler it made for the coming frame ({@code Minecraft.run},
     * one hook): the same profiler, or one that also feeds this one while collecting.
     */
    public static ProfilerFiller decorate(ProfilerFiller filler) {
        Profiling profiling = INSTANCE;
        profiling.markStartup();
        if (!profiling.requested && !profiling.overlay) {
            profiling.lastFrameEnd = 0;
            return filler;
        }
        return ProfilerFiller.combine(filler, profiling.filler);
    }

    /** Once a client tick: the overlay's key. */
    public static void tick() {
        while (IncariKeys.PROFILER_OVERLAY.consumeClick()) {
            INSTANCE.overlay = !INSTANCE.overlay;
        }
    }

    /** The frames the overlay draws from. */
    FrameHistory history() {
        return history;
    }

    /** The last frame collected, or null before the first. */
    @Nullable
    FrameSample last() {
        return last;
    }

    /** Memory as of the last frame collected. */
    MemoryUsage memory() {
        return memory;
    }

    /** Called by {@link FrameProfiler} at the end of each frame it timed. */
    void frameEnded(double tickMillis, double extractMillis, double renderMillis) {
        PlatformProfiling platform = ClientPlatform.get().profiling();
        long now = ClientPlatform.get().nanoTime();
        if (lastFrameEnd != 0) {
            history.add((now - lastFrameEnd) / 1_000_000.0);
        }
        lastFrameEnd = now;
        GpuFrameStats gpu = GpuFrameStats.current();
        FrameSample sample = new FrameSample(
                tickMillis,
                extractMillis,
                renderMillis,
                gpu == null ? Double.NaN : gpu.gpuMillis(),
                gpu == null ? -1 : gpu.drawCalls(),
                gpu == null ? -1 : gpu.terrainDrawCalls(),
                gpu == null ? -1 : gpu.terrainSections(),
                meshQueue());
        last = sample;
        memory = platform.memory();
        platform.frameProfiled(sample);
    }

    private static int meshQueue() {
        SectionRenderDispatcher dispatcher =
                Minecraft.getInstance().levelRenderer.sectionRenderDispatcher();
        // Sections waiting to be meshed, and those in the mesher workers' hands.
        return (dispatcher == null ? 0 : dispatcher.getCompileQueueSize())
                + incari.client.render.mesher.Meshing.inFlight();
    }

    /**
     * Marks the end of the first resource reload (the game opens the title screen under the
     * loading screen as it starts to fade: start-up's split, ADR 0026), the title screen's first
     * frame and a world's first frame, once each.
     */
    private void markStartup() {
        if (titleMarked && worldMarked) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!reloadedMarked && minecraft.gui.screen() instanceof TitleScreen) {
            reloadedMarked = true;
            ClientPlatform.get().profiling().mark("reloaded");
        }
        if (minecraft.gui.overlay() != null) {
            return;
        }
        if (!titleMarked && minecraft.gui.screen() instanceof TitleScreen) {
            titleMarked = true;
            ClientPlatform.get().profiling().mark("title");
        }
        if (!worldMarked && minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null) {
            worldMarked = true;
            ClientPlatform.get().profiling().mark("world");
        }
    }
}
