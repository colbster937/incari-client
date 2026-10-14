package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.COPY_READ_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.COPY_WRITE_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DYNAMIC_DRAW;
import static org.teavm.jso.webgl.WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.STATIC_DRAW;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGLBuffer;

/**
 * A buffer as two copies: the game's (LWJGL {@code MemoryUtil} memory, since the game copies into
 * mapped memory natively by address) and WebGL's. WebGL2 can't map buffers, so the game's copy is
 * the truth: all data (writes, mapped writes, copies, texture read-backs) lands there first and is
 * then uploaded.
 *
 * <p>WebGL forbids an index buffer from ever being bound as anything else, so a buffer with
 * {@link GpuBuffer#USAGE_INDEX} is uploaded through the index target and every other buffer
 * through {@code COPY_WRITE_BUFFER}. These get no WebGL buffer:
 *
 * <ul>
 *   <li>read-back targets, which the GPU never reads;
 *   <li>texel buffers: WebGL2 has none, so the device reads them through a texture copied from the
 *       game's copy ({@link TexelBufferTexture});
 *   <li>indirect command buffers: WebGL2 has no indirect draws, so the render pass reads the game's
 *       copy.
 * </ul>
 *
 * <p>Large draw-only buffers keep no game copy ({@link BufferCopies}): the terrain's vertex heaps,
 * filled only by copies from the staging buffer that go straight to WebGL. Their copies would cost
 * as much linear memory as the heaps take on the GPU (128 MB a layer). If anything asks for their
 * contents, they're read back from WebGL once and kept.
 */
final class WebGl2Buffer extends BaseGpuBuffer {

    /** More than any driver pads a uniform block by: up to a vec4 of std140 rounding, and then some. */
    private static final int UNIFORM_SLACK = 256;

    private static final int GPU_USAGES = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM;

    /** Pages of {@link #pageVersions}: 4 KiB. */
    private static final int PAGE_SHIFT = 12;

    /** The game's sequential quad indices repeat these six, four vertices further each time. */
    private static final int[] QUAD = {0, 1, 2, 2, 3, 0};

    private final WebGl2Device device;
    private final @Nullable WebGLBuffer handle;
    private final int uploadTarget;
    private final long glSize;
    /** The game's copy; null for a buffer only drawn from until something asks for its contents. */
    private @Nullable ByteBuffer memory;

    private @Nullable TexelBufferTexture texelTexture;
    /** Counts changes to the contents, so copies of them (a texel buffer's texture) know when they're stale. */
    private int version;
    /** The {@link #version} {@link #quadSequenceOf} last looked at, and what it found. */
    private int quadCheckedVersion = -1;

    private int quadCheckedSize;
    private boolean quadSequence;
    /**
     * For index buffers: the {@link #version} each 4 KiB page was last written at, so a copy of a
     * range (rebased indices, {@link RebasedIndices}) knows whether it's stale. Made on first write.
     */
    private int @Nullable [] pageVersions;
    /** Copies of this index buffer's ranges with their base vertex added, by first index; made on first use. */
    private @Nullable Int2ObjectOpenHashMap<RebasedIndices.Copy> rebasedCopies;

    /** The range written but not yet sent to WebGL ({@link #deferUpload}); none while {@code dirtyTo} is 0. */
    private int dirtyFrom = Integer.MAX_VALUE;

    private int dirtyTo;

    private boolean closed;

    WebGl2Buffer(WebGl2Device device, @GpuBuffer.Usage int usage, long size) {
        super(usage, size);
        this.device = device;
        this.memory = BufferCopies.keepsCopy(usage, size)
                ? MemoryUtil.memCalloc(Math.toIntExact(size)).order(ByteOrder.nativeOrder())
                : null;
        this.uploadTarget = (usage & GpuBuffer.USAGE_INDEX) != 0 ? ELEMENT_ARRAY_BUFFER : COPY_WRITE_BUFFER;
        // A uniform buffer gets some slack past its end, so a range can be bound at the size a
        // driver rounds a block up to even at the buffer's end (see WebGl2Pipeline#blockSize).
        this.glSize = (usage & GpuBuffer.USAGE_UNIFORM) != 0 ? size + UNIFORM_SLACK : size;
        if ((usage & GPU_USAGES) != 0) {
            handle = device.gl().createBuffer();
            device.gl().bindBuffer(uploadTarget, handle);
            // Written from the CPU often (MAP_WRITE) or once: the only hint WebGL acts on.
            int hint = (usage & GpuBuffer.USAGE_MAP_WRITE) != 0 ? DYNAMIC_DRAW : STATIC_DRAW;
            device.gl().bufferData(uploadTarget, Math.toIntExact(glSize), hint);
        } else {
            handle = null;
        }
    }

    /** The WebGL buffer; only buffers with a GPU usage have one. */
    WebGLBuffer handle() {
        WebGLBuffer current = handle;
        if (current == null || closed) {
            throw new IllegalStateException(
                    "Buffer (usage " + usage() + ") has no WebGL buffer" + (closed ? "; it's closed" : ""));
        }
        return current;
    }

    /** The WebGL buffer's size: the buffer's, plus some slack for a uniform buffer. */
    long glSize() {
        return glSize;
    }

    /** Whether the game's copy is here: else the contents are only in WebGL. */
    boolean hasCopy() {
        return memory != null;
    }

    /**
     * The game's copy of the contents. A buffer that kept none reads its contents back from WebGL
     * now (a wait for the GPU) and keeps them from then on.
     */
    ByteBuffer memory() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
        ByteBuffer current = memory;
        if (current == null) {
            WebGl.warn("[Incari] Reading back a vertex buffer that kept no copy (usage " + usage() + ", " + size()
                    + " bytes): it keeps one from now on");
            current = MemoryUtil.memCalloc(Math.toIntExact(size())).order(ByteOrder.nativeOrder());
            if (handle != null) {
                device.gl().bindBuffer(COPY_READ_BUFFER, handle);
                device.gl().getBufferSubData(COPY_READ_BUFFER, 0, Int8Array.fromJavaBuffer(current));
            }
            memory = current;
        }
        return current;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        int start = Math.toIntExact(offset);
        int count = Math.toIntExact(length);
        ByteBuffer view = memory().slice(start, count).order(ByteOrder.nativeOrder());
        // Written bytes reach WebGL when the view is closed, as unmapping flushes them on a desktop
        // GPU; a uniform buffer's, before the next draw, with whatever else was written meanwhile.
        Runnable onClose;
        if (!write) {
            onClose = () -> {};
        } else if ((usage() & GpuBuffer.USAGE_UNIFORM) != 0 && PendingUploads.enabled) {
            onClose = () -> deferUpload(start, count);
        } else {
            onClose = () -> upload(start, count);
        }
        return new GpuBufferSlice.MappedView(slice(offset, length), view, onClose);
    }

    /** Copies {@code data}'s remaining bytes in at {@code offset}, and on to WebGL. */
    void write(int offset, ByteBuffer data) {
        int length = data.remaining();
        ByteBuffer current = memory;
        if (current == null) {
            uploadFrom(offset, data, data.position(), length);
            return;
        }
        current.put(offset, data, data.position(), length);
        upload(offset, length);
    }

    /**
     * Copies {@code length} bytes of {@code source} from {@code sourceOffset} in at
     * {@code offset}: into the game's copy and on to WebGL, or, for a buffer without one, to WebGL
     * alone.
     */
    void copyFrom(int offset, WebGl2Buffer source, int sourceOffset, int length) {
        ByteBuffer current = memory;
        if (current != null) {
            current.put(offset, source.memory(), sourceOffset, length);
            upload(offset, length);
        } else if (source.hasCopy()) {
            uploadFrom(offset, source.memory(), sourceOffset, length);
        } else {
            // Both only in WebGL: copied there. Neither is an index buffer, which WebGL keeps apart.
            version++;
            if (handle != null && !closed && length > 0) {
                device.gl().bindBuffer(COPY_READ_BUFFER, source.handle());
                device.gl().bindBuffer(COPY_WRITE_BUFFER, handle);
                device.gl().copyBufferSubData(COPY_READ_BUFFER, COPY_WRITE_BUFFER, sourceOffset, offset, length);
            }
        }
    }

    /** Sends {@code length} bytes of {@code data} from {@code from} to WebGL at {@code offset}: a buffer without a copy. */
    private void uploadFrom(int offset, ByteBuffer data, int from, int length) {
        version++;
        if (handle == null || closed || length == 0) {
            return;
        }
        device.gl().bindBuffer(uploadTarget, handle);
        device.staging().bufferSubData(device.gl(), uploadTarget, offset, data, from, length);
    }

    /** How many times the contents have changed. */
    int version() {
        return version;
    }

    /**
     * This buffer as a texel buffer of {@code format}: a texture holding its current contents, made
     * on first use and refreshed when the contents have changed.
     */
    TexelBufferTexture texelTexture(GpuFormat format) {
        TexelBufferTexture texture = texelTexture;
        if (texture == null || texture.format() != format) {
            if (texture != null) {
                texture.close();
            }
            texture = new TexelBufferTexture(device, format, size());
            texelTexture = texture;
        }
        texture.refresh(this);
        return texture;
    }

    /**
     * Whether the contents, read as indices of {@code indexSize} bytes, are the game's sequential
     * quads from vertex 0 on (its shared quad index buffer). Looked at once per change of the
     * contents.
     */
    boolean quadSequenceOf(int indexSize) {
        if (quadCheckedVersion == version && quadCheckedSize == indexSize) {
            return quadSequence;
        }
        quadCheckedVersion = version;
        quadCheckedSize = indexSize;
        ByteBuffer contents = memory();
        int indices = (int) (size() / indexSize) / 6 * 6;
        boolean matches = indices > 0;
        for (int i = 0; matches && i < indices; i++) {
            int value = indexSize == 2
                    ? Short.toUnsignedInt(contents.getShort(i * 2))
                    : indexSize == 4 ? contents.getInt(i * 4) : -1;
            matches = value == i / 6 * 4 + QUAD[i % 6];
        }
        quadSequence = matches;
        return matches;
    }

    /** The copies {@link RebasedIndices} keeps of this buffer's ranges. */
    Int2ObjectOpenHashMap<RebasedIndices.Copy> rebasedCopies() {
        Int2ObjectOpenHashMap<RebasedIndices.Copy> copies = rebasedCopies;
        if (copies == null) {
            copies = new Int2ObjectOpenHashMap<>();
            rebasedCopies = copies;
        }
        return copies;
    }

    /** Whether bytes {@code offset} to {@code offset + length} are unchanged since {@code sinceVersion}. */
    boolean unchangedSince(int offset, int length, int sinceVersion) {
        int[] pages = pageVersions;
        if (pages == null) {
            return true;
        }
        int last = Math.min(pages.length - 1, (offset + Math.max(length, 1) - 1) >> PAGE_SHIFT);
        for (int page = offset >> PAGE_SHIFT; page <= last; page++) {
            if (pages[page] > sinceVersion) {
                return false;
            }
        }
        return true;
    }

    /**
     * Notes that bytes {@code offset} to {@code offset + length} of the game's copy changed, to be
     * sent to WebGL before the next draw with any other range written meanwhile ({@link PendingUploads}).
     */
    private void deferUpload(int offset, int length) {
        if (length == 0) {
            return;
        }
        version++;
        if (dirtyTo == 0) {
            device.pendingUploads().add(this);
        }
        dirtyFrom = Math.min(dirtyFrom, offset);
        dirtyTo = Math.max(dirtyTo, offset + length);
    }

    /** Sends the range {@link #deferUpload} noted, as one upload. */
    void flushUpload() {
        int from = dirtyFrom;
        int to = dirtyTo;
        dirtyFrom = Integer.MAX_VALUE;
        dirtyTo = 0;
        if (to > from) {
            upload(from, to - from);
        }
    }

    /** Sends a range of the game's copy to WebGL. */
    void upload(int offset, int length) {
        version++;
        if ((usage() & GpuBuffer.USAGE_INDEX) != 0 && length > 0) {
            int[] pages = pageVersions;
            if (pages == null) {
                pages = new int[(int) ((size() >> PAGE_SHIFT) + 1)];
                pageVersions = pages;
            }
            int last = Math.min(pages.length - 1, (offset + length - 1) >> PAGE_SHIFT);
            for (int page = offset >> PAGE_SHIFT; page <= last; page++) {
                pages[page] = version;
            }
        }
        if (handle == null || closed || length == 0) {
            return;
        }
        device.gl().bindBuffer(uploadTarget, handle);
        device.staging().bufferSubData(device.gl(), uploadTarget, offset, memory(), offset, length);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (handle != null) {
            device.gl().deleteBuffer(handle);
        }
        TexelBufferTexture texture = texelTexture;
        if (texture != null) {
            texture.close();
            texelTexture = null;
        }
        if (memory != null) {
            MemoryUtil.memFree(memory);
            memory = null;
        }
    }
}
