package incari.app.web.headless;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-frame scratch memory as fresh {@code MemoryUtil} allocations: nothing is uploaded, so
 * nothing needs pooling. Uploads copy their data, so later reads of the slices see it. Everything
 * handed out lives until the frame's commands are submitted ({@link #release}), as transient
 * memory does on a real device.
 */
final class HeadlessTransientMemory implements TransientMemory {

    private final List<ByteBuffer> cpu = new ArrayList<>();
    private final List<HeadlessBuffer> buffers = new ArrayList<>();

    /** Frees this frame's allocations; the encoder calls it on submit. */
    void release() {
        for (ByteBuffer buffer : cpu) {
            MemoryUtil.memFree(buffer);
        }
        cpu.clear();
        for (HeadlessBuffer buffer : buffers) {
            buffer.close();
        }
        buffers.clear();
    }

    private HeadlessBuffer track(HeadlessBuffer buffer) {
        buffers.add(buffer);
        return buffer;
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        ByteBuffer buffer = MemoryUtil.memCalloc(Math.toIntExact(size)).order(ByteOrder.nativeOrder());
        cpu.add(buffer);
        return buffer;
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
            long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return track(new HeadlessBuffer(usage, size)).map(true, true);
    }

    @Override
    public GpuBufferSlice allocateGpu(
            long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return track(new HeadlessBuffer(usage, size)).slice();
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
            long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return track(new HeadlessBuffer(usage, size)).map(true, true);
    }

    @Override
    public GpuBufferSlice uploadStaging(
            List<ByteBuffer> data,
            long alignment,
            @GpuBuffer.Usage int usage,
            long minimumAllocation,
            long elementSize) {
        return upload(data, usage);
    }

    @Override
    public GpuBufferSlice uploadGpu(
            List<ByteBuffer> data,
            long alignment,
            @GpuBuffer.Usage int usage,
            long minimumAllocation,
            long elementSize) {
        return upload(data, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return multiUpload(data, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return multiUpload(data, usage);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, @GpuBuffer.Usage int usage) {
        long size = 0;
        for (ByteBuffer part : data) {
            size += part.remaining();
        }
        HeadlessBuffer buffer = track(new HeadlessBuffer(usage, size));
        long offset = 0;
        for (ByteBuffer part : data) {
            buffer.write(offset, part);
            offset += part.remaining();
        }
        return buffer.slice();
    }

    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, @GpuBuffer.Usage int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer part : data) {
            slices.add(upload(List.of(part), usage));
        }
        return slices;
    }
}
