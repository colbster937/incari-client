package incari.render.webgl2;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Memory that lives until the frame's commands are submitted: uniforms, streamed vertices, staging.
 * GPU memory is carved from blocks, one pool per usage (WebGL won't let an index buffer be anything
 * else, and uniform offsets have their own alignment), and the blocks are reused every frame, so a
 * steady frame allocates no WebGL buffers. WebGL runs commands in order, so reusing a block after
 * submit can't overwrite data a draw still needs.
 */
final class WebGl2TransientMemory implements TransientMemory {

    /** Big enough for a frame's GUI and uniforms in one block; bigger requests get their own. */
    private static final int BLOCK_SIZE = 1 << 20;

    private final WebGl2Device device;
    private final Map<Integer, List<Block>> pools = new HashMap<>();
    private final List<ByteBuffer> cpu = new ArrayList<>();

    WebGl2TransientMemory(WebGl2Device device) {
        this.device = device;
    }

    /** Frees this frame's CPU memory and rewinds every block; the encoder calls it on submit. */
    void release() {
        for (ByteBuffer buffer : cpu) {
            MemoryUtil.memFree(buffer);
        }
        cpu.clear();
        for (List<Block> blocks : pools.values()) {
            for (Block block : blocks) {
                block.used = 0;
            }
        }
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
        return allocate(size, alignment, usage).map(true, true);
    }

    @Override
    public GpuBufferSlice allocateGpu(
            long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return allocate(size, alignment, usage);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
            long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return allocate(size, alignment, usage).map(true, true);
    }

    @Override
    public GpuBufferSlice uploadStaging(
            List<ByteBuffer> data,
            long alignment,
            @GpuBuffer.Usage int usage,
            long minimumAllocation,
            long elementSize) {
        return upload(data, alignment, usage);
    }

    @Override
    public GpuBufferSlice uploadGpu(
            List<ByteBuffer> data,
            long alignment,
            @GpuBuffer.Usage int usage,
            long minimumAllocation,
            long elementSize) {
        return upload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return multiUpload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return multiUpload(data, alignment, usage);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        long size = 0;
        for (ByteBuffer part : data) {
            size += part.remaining();
        }
        GpuBufferSlice slice = allocate(size, alignment, usage);
        WebGl2Buffer buffer = (WebGl2Buffer) slice.buffer();
        ByteBuffer memory = buffer.memory();
        int offset = Math.toIntExact(slice.offset());
        for (ByteBuffer part : data) {
            memory.put(offset, part, part.position(), part.remaining());
            offset += part.remaining();
        }
        buffer.upload(Math.toIntExact(slice.offset()), Math.toIntExact(size));
        return slice;
    }

    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        List<GpuBufferSlice> slices = new ArrayList<>(data.size());
        for (ByteBuffer part : data) {
            slices.add(upload(List.of(part), alignment, usage));
        }
        return slices;
    }

    private GpuBufferSlice allocate(long size, long alignment, @GpuBuffer.Usage int usage) {
        // Every transient buffer can be written from the CPU, whatever else it's for.
        int blockUsage = usage | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST;
        List<Block> blocks = pools.computeIfAbsent(blockUsage, u -> new ArrayList<>());
        long align = Math.max(1, alignment);
        for (Block block : blocks) {
            long start = (block.used + align - 1) / align * align;
            if (start + size <= block.buffer.size()) {
                block.used = start + size;
                return block.buffer.slice(start, size);
            }
        }
        Block block = new Block(new WebGl2Buffer(device, blockUsage, Math.max(BLOCK_SIZE, size)));
        blocks.add(block);
        block.used = size;
        return block.buffer.slice(0, size);
    }

    private static final class Block {
        final WebGl2Buffer buffer;
        long used;

        Block(WebGl2Buffer buffer) {
            this.buffer = buffer;
        }
    }
}
