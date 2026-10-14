package incari.app.web.headless;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * A buffer as plain memory, allocated when first mapped or written: the game writes uniforms and
 * vertices into mapped buffers and sometimes reads them back, so that has to work even with
 * nothing to draw. The memory comes from LWJGL's {@code MemoryUtil}, since the game treats mapped
 * memory as native ({@code memCopy}, {@code memAddress}); closing frees it.
 */
final class HeadlessBuffer implements GpuBuffer {

    private final @GpuBuffer.Usage int usage;
    private final long size;
    private @Nullable ByteBuffer memory;
    private boolean closed;

    HeadlessBuffer(@GpuBuffer.Usage int usage, long size) {
        this.usage = usage;
        this.size = size;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public @GpuBuffer.Usage int usage() {
        return usage;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        ByteBuffer view = memory().duplicate().order(ByteOrder.nativeOrder());
        view.limit(Math.toIntExact(offset + length)).position(Math.toIntExact(offset));
        return new GpuBufferSlice.MappedView(
                slice(offset, length), view.slice().order(ByteOrder.nativeOrder()), () -> {});
    }

    void write(long offset, ByteBuffer data) {
        ByteBuffer target = memory().duplicate();
        target.position(Math.toIntExact(offset));
        target.put(data.duplicate());
    }

    private ByteBuffer memory() {
        ByteBuffer current = memory;
        if (current == null) {
            current = MemoryUtil.memCalloc(Math.toIntExact(size)).order(ByteOrder.nativeOrder());
            memory = current;
        }
        return current;
    }

    @Override
    public void close() {
        closed = true;
        MemoryUtil.memFree(memory);
        memory = null;
    }
}
