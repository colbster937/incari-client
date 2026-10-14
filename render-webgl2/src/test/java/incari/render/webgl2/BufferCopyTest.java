package incari.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.junit.jupiter.api.Test;

/** Which buffers keep the game's copy of their contents. */
class BufferCopyTest {

    private static final int HEAP = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;

    @Test
    void theTerrainsVertexHeapsKeepNone() {
        assertThat(BufferCopies.keepsCopy(HEAP, 32 << 20)).isFalse();
        assertThat(BufferCopies.keepsCopy(HEAP, 128 << 20)).isFalse();
    }

    @Test
    void smallVertexBuffersKeepTheirs() {
        // Per-instance rows the render pass reads.
        assertThat(BufferCopies.keepsCopy(GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 8192))
                .isTrue();
    }

    @Test
    void buffersTheCpuReadsKeepTheirs() {
        int large = 32 << 20;
        assertThat(BufferCopies.keepsCopy(HEAP | GpuBuffer.USAGE_MAP_WRITE, large))
                .isTrue();
        assertThat(BufferCopies.keepsCopy(HEAP | GpuBuffer.USAGE_MAP_READ, large))
                .isTrue();
        // The index heaps: translucent sections' indices are rebased from the copy.
        assertThat(BufferCopies.keepsCopy(
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, large))
                .isTrue();
        assertThat(BufferCopies.keepsCopy(GpuBuffer.USAGE_UNIFORM, large)).isTrue();
        assertThat(BufferCopies.keepsCopy(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_WRITE, large))
                .isTrue();
    }
}
