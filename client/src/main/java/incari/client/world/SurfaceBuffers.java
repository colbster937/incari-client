package incari.client.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;

/**
 * The surface rules' prefilled density volumes, reused from one chunk to the next. The ore vein
 * rules sample their densities over every block of the chunk up to its highest section, four
 * volumes of about 50,000 floats a chunk, each a new array: about 200 kB apiece, past V8's limit
 * for ordinary objects, so every one went to its large-object space, which only a full collection
 * empties. Here a thread keeps the buffers it filled for the last chunk and fills them again for the
 * next, cleared first, so they read exactly as new ones.
 *
 * <p>A chunk's surface is built on one thread, start to end, and nothing reads its buffers after
 * ({@code MaterialSystem.buildSurface} makes and drops the rules that hold them); the next chunk on
 * the thread takes them in the same order. Small volumes (the carvers' one-block surface checks)
 * are made new, as before.
 */
public final class SurfaceBuffers {

    /** Smaller volumes than this are made new: not worth keeping. */
    private static final int MIN_POOLED = 4096;

    private static boolean enabled;

    private static final ThreadLocal<Slots> SLOTS = ThreadLocal.withInitial(Slots::new);

    private SurfaceBuffers() {}

    /** Off unless the page turns it on ({@code ?surfacebuffers=game} leaves it off, to compare). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Hook ({@code MaterialSystem.buildSurface}, before its rules are compiled): a new chunk. */
    public static void begin() {
        if (enabled) {
            SLOTS.get().next = 0;
        }
    }

    /** Hook ({@code MaterialRuleContext.getDensitiesInChunk}): a zeroed buffer of {@code size}. */
    public static DensityBuffer take(int size) {
        if (!enabled || size < MIN_POOLED) {
            return DensityBuffer.createUnpooled(size);
        }
        Slots slots = SLOTS.get();
        int slot = slots.next++;
        if (slot < slots.buffers.size()) {
            Reused buffer = slots.buffers.get(slot);
            if (buffer.capacity() >= size) {
                buffer.reuse(size);
                return buffer;
            }
            Reused larger = new Reused(size);
            slots.buffers.set(slot, larger);
            return larger;
        }
        Reused buffer = new Reused(size);
        slots.buffers.add(buffer);
        return buffer;
    }

    /** One thread's buffers, in the order the last chunk took them. */
    private static final class Slots {
        final List<Reused> buffers = new ArrayList<>();
        int next;
    }

    /** A density buffer whose size can be set again, up to its capacity. */
    private static final class Reused extends DensityBuffer {
        Reused(int size) {
            super(size);
        }

        void reuse(int size) {
            this.size = size;
            // Zeroed, as a new buffer starts: a sampler may add to what it's given.
            fill(0.0F);
        }
    }
}
