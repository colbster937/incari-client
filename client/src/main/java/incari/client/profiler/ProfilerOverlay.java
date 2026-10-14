package incari.client.profiler;

import incari.platform.api.profile.FrameSample;
import incari.platform.api.profile.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * The profiler's overlay, in the top right corner over every screen, from one hook in
 * {@code Gui}: frame rate and 1% low, where the frame's time goes (tick, extract, render submit,
 * GPU), draw calls, the mesh queue and memory, over a graph of the last frames' lengths. F9
 * ({@code IncariKeys.PROFILER_OVERLAY}) shows and hides it.
 *
 * <p>The text is rebuilt four times a second, not every frame, so the overlay costs little of what
 * it measures.
 */
public final class ProfilerOverlay {

    private static final long REFRESH_NANOS = 250_000_000L;
    private static final int MARGIN = 2;
    private static final int LINE_HEIGHT = 10;
    private static final int GRAPH_HEIGHT = 40;
    /** The frame length at the graph's top. */
    private static final double GRAPH_MILLIS = 50;

    private static final int BACKGROUND = 0x90000000;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int GOOD = 0xFF55FF55;
    private static final int SLOW = 0xFFFFFF55;
    private static final int BAD = 0xFFFF5555;
    private static final int GUIDE = 0x80FFFFFF;

    private static List<String> lines = List.of();
    private static long builtAt;

    private ProfilerOverlay() {}

    public static void extract(GuiGraphicsExtractor graphics) {
        if (!Profiling.overlayShown()) {
            return;
        }
        Profiling profiling = Profiling.get();
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        long now = System.nanoTime();
        if (lines.isEmpty() || now - builtAt >= REFRESH_NANOS) {
            lines = describe(profiling.history(), profiling.last(), profiling.memory());
            builtAt = now;
        }
        FrameHistory history = profiling.history();
        int width = Math.min(history.capacity(), graphics.guiWidth() / 3);
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int right = graphics.guiWidth() - MARGIN;
        int left = right - width;
        int top = MARGIN;
        int textBottom = top + lines.size() * LINE_HEIGHT;
        int bottom = textBottom + GRAPH_HEIGHT + MARGIN;
        graphics.fill(left - MARGIN, top - MARGIN, right + MARGIN, bottom, BACKGROUND);
        int y = top;
        for (String line : lines) {
            graphics.text(font, line, left, y, TEXT, false);
            y += LINE_HEIGHT;
        }
        int graphBottom = bottom - MARGIN;
        int frames = Math.min(history.size(), width);
        for (int age = 0; age < frames; age++) {
            double millis = history.ago(age);
            int height = (int) Math.round(Math.min(millis, GRAPH_MILLIS) / GRAPH_MILLIS * GRAPH_HEIGHT);
            int x = right - 1 - age;
            graphics.fill(x, graphBottom - Math.max(1, height), x + 1, graphBottom, colour(millis));
        }
        // 60 frames a second.
        int sixty = graphBottom - (int) Math.round(1000.0 / 60 / GRAPH_MILLIS * GRAPH_HEIGHT);
        graphics.fill(left, sixty, right, sixty + 1, GUIDE);
    }

    static List<String> describe(FrameHistory history, @Nullable FrameSample sample, MemoryUsage memory) {
        List<String> out = new ArrayList<>(4);
        double lowMillis = history.slowestMean(1);
        out.add("Incari profiler: " + whole(history.framesPerSecond(1000)) + " fps, 1% low "
                + (Double.isNaN(lowMillis) ? "n/a" : whole(1000 / lowMillis)) + " fps");
        if (sample == null) {
            out.add("Collecting...");
            return out;
        }
        double frame = history.size() == 0 ? Double.NaN : history.ago(0);
        out.add("Frame " + tenths(frame) + " ms: tick " + tenths(sample.tickMillis()) + ", extract "
                + tenths(sample.extractMillis()) + ", render " + tenths(sample.renderMillis()) + ", GPU "
                + tenths(sample.gpuMillis()));
        out.add((sample.drawCalls() < 0
                        ? "Draw calls n/a"
                        : "Draws " + sample.drawCalls() + " (terrain " + sample.terrainDrawCalls() + " for "
                                + sample.terrainSections() + " sections)")
                + ", mesh queue " + sample.meshQueue());
        out.add("Heap " + megabytes(memory.heapUsed()) + " of " + megabytes(memory.heapLimit()) + " MB, native "
                + megabytes(memory.nativeBytes()) + " MB, "
                + (memory.collections() < 0 ? "GCs n/a" : memory.collections() + " GCs"));
        return out;
    }

    private static int colour(double millis) {
        if (millis <= 1000.0 / 55) {
            return GOOD;
        }
        return millis <= 1000.0 / 28 ? SLOW : BAD;
    }

    private static String whole(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "n/a" : Long.toString(Math.round(value));
    }

    /** One decimal place, without {@code String.format}'s cost on a frame's path. */
    static String tenths(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "n/a";
        }
        long tenths = Math.round(value * 10);
        return (tenths < 0 ? "-" : "") + Math.abs(tenths) / 10 + "." + Math.abs(tenths) % 10;
    }

    private static String megabytes(long bytes) {
        return bytes < 0 ? "n/a" : Long.toString(Math.round(bytes / 1048576.0));
    }
}
