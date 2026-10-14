package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.STATIC_DRAW;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGLBuffer;

/**
 * Indices with their draw's base vertex already added (ADR 0022), for draws with index buffers of
 * their own, which the backend's sequential quads can't replace. The game sorts each translucent
 * section's quads back to front into indices counted from the section's first vertex; WebGL2 has
 * no base vertex, so each section would be its own draw. Rebased, a heap's translucent sections
 * are one multi-draw, still in the game's order.
 *
 * <p>The copies live in one element buffer (the arena) and are kept from frame to frame: a
 * section's copy is reused until the game writes that part of its index heap again (a new sort,
 * {@link WebGl2Buffer#unchangedSince}), so a frame copies only the sections re-sorted since the last.
 * A re-sorted section has as many indices as before, so its new copy takes the old one's place
 * (ADR 0027); only a section new to the arena, or one whose size or base vertex changed, is
 * appended. When the arena fills, it starts over and everything is copied again.
 *
 * <p>Hot path: nothing allocates once the arena, the staging buffer and the caches have grown.
 */
final class RebasedIndices {

    private static final int MIN_INDICES = 1 << 18;

    /** A copy: where it is in the arena, and what it was copied from. */
    static final class Copy {
        int epoch;
        int offset;
        int count;
        int baseVertex;
        int indexSize;
        int version;
    }

    private final WebGl2Device device;
    private final WebGLBuffer arena;
    /** Counts the arena's restarts: a copy from an earlier one is gone. */
    private int epoch;

    private int capacity;
    private int used;
    /** Where this batch's new copies start in the arena; they're staged until {@link #upload}. */
    private int stagedFrom;

    private ByteBuffer staging = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN);
    /** A kept view of the staging buffer (hot path: none made per upload), remade when detached or replaced. */
    private @Nullable Int8Array stagingView;

    /** Copies rewritten in place this batch, staged one after another (see {@link #rewrite}). */
    private ByteBuffer rewrites = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);

    private @Nullable Int8Array rewritesView;
    /** Per rewrite: where it goes in the arena, where it is in {@link #rewrites}, and its count (indices). */
    private int[] rewriteRanges = new int[3 * 64];

    private int rewriteCount;
    private int rewriteUsed;

    RebasedIndices(WebGl2Device device) {
        this.device = device;
        this.arena = device.gl().createBuffer();
        resize(MIN_INDICES);
    }

    /** Starts a batch that will need at most {@code indices} new indices. */
    void begin(long indices) {
        if (used + indices > capacity) {
            // Start over: every copy is made again, in a larger arena if one batch needs it.
            epoch++;
            used = 0;
            if (indices > capacity) {
                long size = capacity;
                while (size < indices) {
                    size *= 2;
                }
                resize((int) Math.min(size, Integer.MAX_VALUE / 4));
            }
        }
        stagedFrom = used;
    }

    /**
     * The byte offset in the arena of {@code indexCount} indices of {@code indexSize} bytes from
     * index {@code firstIndex} of {@code source}, each plus {@code baseVertex}: a kept copy if it's
     * still good, else a new one.
     */
    int offsetOf(WebGl2Buffer source, int indexSize, int firstIndex, int indexCount, int baseVertex) {
        Int2ObjectOpenHashMap<Copy> kept = source.rebasedCopies();
        Copy copy = kept.get(firstIndex);
        if (copy != null
                && copy.epoch == epoch
                && copy.count == indexCount
                && copy.baseVertex == baseVertex
                && copy.indexSize == indexSize
                && source.unchangedSince(firstIndex * indexSize, indexCount * indexSize, copy.version)) {
            return copy.offset * 4;
        }
        if (copy != null
                && copy.epoch == epoch
                && copy.count == indexCount
                && copy.baseVertex == baseVertex
                && copy.indexSize == indexSize) {
            // Re-sorted: the same indices in a new order, written over the old copy.
            copy.version = source.version();
            rewrite(source.memory(), indexSize, firstIndex, indexCount, baseVertex, copy.offset);
            return copy.offset * 4;
        }
        if (copy == null) {
            copy = new Copy();
            kept.put(firstIndex, copy);
        }
        copy.epoch = epoch;
        copy.offset = used;
        copy.count = indexCount;
        copy.baseVertex = baseVertex;
        copy.indexSize = indexSize;
        copy.version = source.version();
        stage(source.memory(), indexSize, firstIndex, indexCount, baseVertex);
        return copy.offset * 4;
    }

    /** Uploads this batch's new copies and binds the arena as the element array buffer. */
    void upload() {
        device.gl().bindBuffer(ELEMENT_ARRAY_BUFFER, arena);
        int bytes = (used - stagedFrom) * 4;
        if (bytes > 0) {
            Int8Array view = stagingView;
            if (view == null || !WebGl.bufferSubData(device.gl(), ELEMENT_ARRAY_BUFFER, stagedFrom * 4, view, bytes)) {
                view = Int8Array.fromJavaBuffer(staging);
                stagingView = view;
                WebGl.bufferSubData(device.gl(), ELEMENT_ARRAY_BUFFER, stagedFrom * 4, view, bytes);
            }
        }
        stagedFrom = used;
        for (int i = 0; i < rewriteCount; i++) {
            int at = rewriteRanges[i * 3] * 4;
            int from = rewriteRanges[i * 3 + 1] * 4;
            int length = rewriteRanges[i * 3 + 2] * 4;
            Int8Array view = rewritesView;
            if (view == null || !WebGl.bufferSubDataFrom(device.gl(), ELEMENT_ARRAY_BUFFER, at, view, from, length)) {
                view = Int8Array.fromJavaBuffer(rewrites);
                rewritesView = view;
                WebGl.bufferSubDataFrom(device.gl(), ELEMENT_ARRAY_BUFFER, at, view, from, length);
            }
        }
        rewriteCount = 0;
        rewriteUsed = 0;
    }

    /** Stages {@code indexCount} rebased indices to go over the copy at arena index {@code at} on {@link #upload}. */
    private void rewrite(ByteBuffer source, int indexSize, int firstIndex, int indexCount, int baseVertex, int at) {
        if ((long) (rewriteUsed + indexCount) * 4 > rewrites.capacity()) {
            int size = rewrites.capacity();
            while (size < (rewriteUsed + indexCount) * 4) {
                size *= 2;
            }
            ByteBuffer grown = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
            grown.put(0, rewrites, 0, rewriteUsed * 4);
            rewrites = grown;
            rewritesView = null;
        }
        if (rewriteCount * 3 + 3 > rewriteRanges.length) {
            rewriteRanges = Arrays.copyOf(rewriteRanges, rewriteRanges.length * 2);
        }
        rewriteRanges[rewriteCount * 3] = at;
        rewriteRanges[rewriteCount * 3 + 1] = rewriteUsed;
        rewriteRanges[rewriteCount * 3 + 2] = indexCount;
        rewriteCount++;
        copyRebased(source, indexSize, firstIndex, indexCount, baseVertex, rewrites, rewriteUsed);
        rewriteUsed += indexCount;
    }

    private void stage(ByteBuffer source, int indexSize, int firstIndex, int indexCount, int baseVertex) {
        int at = used - stagedFrom;
        ensureStaging(at + indexCount);
        copyRebased(source, indexSize, firstIndex, indexCount, baseVertex, staging, at);
        used += indexCount;
    }

    /**
     * Writes the indices from {@code firstIndex}, each plus {@code baseVertex}, as 32-bit ints from
     * index {@code at} of {@code target}. Hot (every re-sorted translucent section's indices, each
     * frame the camera moves): straight loads and stores in linear memory, the ranges checked once
     * rather than at every index as the buffers' own accessors check them.
     */
    private static void copyRebased(
            ByteBuffer source,
            int indexSize,
            int firstIndex,
            int indexCount,
            int baseVertex,
            ByteBuffer target,
            int at) {
        Objects.checkFromIndexSize(firstIndex * indexSize, indexCount * indexSize, source.capacity());
        Objects.checkFromIndexSize(at * 4, indexCount * 4, target.capacity());
        long from = MemoryUtil.memAddress(source) - source.position() + (long) firstIndex * indexSize;
        long to = MemoryUtil.memAddress(target) - target.position() + (long) at * 4;
        if (indexSize == 2) {
            for (int i = 0; i < indexCount; i++) {
                MemoryUtil.memPutInt(
                        to + i * 4L, Short.toUnsignedInt(MemoryUtil.memGetShort(from + i * 2L)) + baseVertex);
            }
        } else {
            for (int i = 0; i < indexCount; i++) {
                MemoryUtil.memPutInt(to + i * 4L, MemoryUtil.memGetInt(from + i * 4L) + baseVertex);
            }
        }
    }

    private void resize(int indices) {
        capacity = indices;
        device.gl().bindBuffer(ELEMENT_ARRAY_BUFFER, arena);
        device.gl().bufferData(ELEMENT_ARRAY_BUFFER, indices * 4, STATIC_DRAW);
    }

    private void ensureStaging(int indices) {
        if ((long) indices * 4 <= staging.capacity()) {
            return;
        }
        int size = staging.capacity();
        while (size < indices * 4) {
            size *= 2;
        }
        ByteBuffer grown = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        grown.put(0, staging, 0, (used - stagedFrom) * 4);
        staging = grown;
        stagingView = null;
    }
}
