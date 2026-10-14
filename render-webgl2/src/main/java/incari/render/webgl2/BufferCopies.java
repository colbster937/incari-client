package incari.render.webgl2;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

/**
 * Which buffers keep the game's copy of their contents (ADR 0025). Every buffer used to (ADR
 * 0007): the game's copy is what writes, maps and copies go through first. A large buffer that is
 * only drawn from keeps none: a vertex buffer of a megabyte or more that is never mapped and serves
 * nothing the CPU reads (the terrain's vertex heaps, whose copies cost as much linear memory as the
 * heaps take on the GPU). Small vertex buffers keep theirs: among them the per-instance rows the
 * render pass reads, and their copies cost nothing worth saving.
 */
final class BufferCopies {

    /** Below this a buffer keeps its copy whatever its usage. */
    static final long LARGE = 1 << 20;

    /** Usages that read the game's copy. */
    private static final int CPU_USAGES = GpuBuffer.USAGE_MAP_READ
            | GpuBuffer.USAGE_MAP_WRITE
            | GpuBuffer.USAGE_INDEX
            | GpuBuffer.USAGE_UNIFORM
            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
            | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    /** Every buffer keeps a copy, as before ADR 0025, when the page asks ({@code ?copies=all}), to compare. */
    static volatile boolean keepAll;

    private BufferCopies() {}

    /** Whether a buffer of {@code usage} and {@code size} keeps the game's copy from the start. */
    static boolean keepsCopy(@GpuBuffer.Usage int usage, long size) {
        return keepAll || size < LARGE || (usage & GpuBuffer.USAGE_VERTEX) == 0 || (usage & CPU_USAGES) != 0;
    }
}
