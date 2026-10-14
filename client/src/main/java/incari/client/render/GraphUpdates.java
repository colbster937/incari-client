package incari.client.render;

import incari.client.platform.ClientPlatform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The occlusion graph's full update spread over frames (ADR 0031). The game rebuilds the graph that
 * decides which sections are seen on a background thread, alongside the frames, whenever the camera
 * jumps or the view changes. In the page the background pool runs between frames, and a task runs
 * to its end once started: a full rebuild took 40 ms and more on the floor device, all in one go,
 * past its slot and into the next frame. Now it checks the slot as it goes, and past the slot's end
 * waits for the next one, as a section's snapshot does ({@code PlatformThreads.pauseIfLate}). The
 * game expects the rebuild to take frames anyway (the frames go on with the last graph meanwhile),
 * so a slower one changes only when the new graph arrives, as a slower thread would.
 *
 * <p>On the render thread (the graph's partial updates) and on the desktop, it does nothing.
 */
public final class GraphUpdates {

    /** Nodes between looks at the clock: a node is a few hash lookups, the clock a call out. */
    private static final int NODES_PER_CHECK = 64;

    // Counted from any thread: a race costs a look at the clock, nothing else.
    private static int nodes;

    private static volatile boolean enabled = true;

    private static final LongOpenHashSet keptEmptySections = new LongOpenHashSet();
    private static final LongOpenHashSet keptLoadedChunks = new LongOpenHashSet();

    private GraphUpdates() {}

    /** Whether the update pauses (the page's {@code ?graph=game} runs it whole, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * Hook ({@code SectionOcclusionGraph.scheduleFullUpdate}): the copy of the empty sections a full
     * update reads while the frames go on changing the set (ADR 0032). The game clones the set each
     * time, about 5,000 sections at render distance 8 in arrays of 256 KB; here one kept set is
     * refilled. A full update is only scheduled once the last has finished, so nothing reads the
     * kept set any more when it's refilled. Off ({@code ?graph=game}), a clone as the game makes.
     */
    public static LongOpenHashSet emptySectionsCopy(LongOpenHashSet emptySections) {
        if (!enabled) {
            return emptySections.clone();
        }
        return refill(keptEmptySections, emptySections);
    }

    /** Hook ({@code SectionOcclusionGraph.scheduleFullUpdate}): the loaded chunks' copy, likewise. */
    public static LongOpenHashSet loadedChunksCopy(LongOpenHashSet loadedChunks) {
        if (!enabled) {
            return loadedChunks.clone();
        }
        return refill(keptLoadedChunks, loadedChunks);
    }

    private static LongOpenHashSet refill(LongOpenHashSet kept, LongOpenHashSet from) {
        kept.clear();
        kept.addAll(from);
        return kept;
    }

    /** Hook ({@code SectionOcclusionGraph.runUpdates}, once a node). */
    public static void node() {
        if (enabled && ++nodes % NODES_PER_CHECK == 0) {
            ClientPlatform.get().threads().pauseIfLate();
        }
    }
}
