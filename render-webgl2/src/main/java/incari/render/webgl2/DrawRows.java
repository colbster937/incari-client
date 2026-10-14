package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.DYNAMIC_DRAW;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNIFORM_BUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNSIGNED_INT;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGLBuffer;

/**
 * The rows the multi-draw pipelines read by {@code gl_DrawID} ({@link DrawIdRewrite}): one
 * per sub-draw, holding what the game meant as that draw's per-instance data. Rows are filled here
 * and uploaded to a ring of uniform buffer space, one batch per draw call, which is then bound as
 * the {@link DrawIdRewrite#BLOCK} block from the batch's first row, so row {@code i} is sub-draw
 * {@code i}.
 *
 * <p>A batch always binds a whole block's worth (WebGL checks the bound range against the block's
 * size), so the ring has a block of slack past its end. Hot path: no allocation.
 */
final class DrawRows {

    /** Batches of rows the ring holds before it starts over; a frame's terrain needs a few dozen. */
    private static final int RING_BYTES = 256 * 1024;

    private static final int BLOCK_BYTES = DrawIdRewrite.ROWS * DrawIdRewrite.ROW_BYTES;

    private final WebGl2Device device;
    private final WebGLBuffer ring;
    private final int binding;
    private final int alignment;
    private final ByteBuffer rows = ByteBuffer.allocateDirect(BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    /** Each sub-draw's index count and byte offset, for {@code multiDrawElementsWEBGL}. */
    private final ByteBuffer counts =
            ByteBuffer.allocateDirect(4 * DrawIdRewrite.ROWS).order(ByteOrder.LITTLE_ENDIAN);

    private final ByteBuffer offsets =
            ByteBuffer.allocateDirect(4 * DrawIdRewrite.ROWS).order(ByteOrder.LITTLE_ENDIAN);
    private int count;
    private int next;
    /** Kept views of the buffers above (hot path: none made per call), remade when detached. */
    private @Nullable Int8Array rowsView;

    private @Nullable JSObject intViews;

    /**
     * @param binding the uniform buffer binding point the block is bound at, which no pipeline's
     *     own uniforms use
     * @param alignment {@code UNIFORM_BUFFER_OFFSET_ALIGNMENT}
     */
    DrawRows(WebGl2Device device, int binding, int alignment) {
        this.device = device;
        this.binding = binding;
        this.alignment = Math.max(4, alignment);
        this.ring = device.gl().createBuffer();
        device.gl().bindBuffer(UNIFORM_BUFFER, ring);
        device.gl().bufferData(UNIFORM_BUFFER, RING_BYTES + BLOCK_BYTES, DYNAMIC_DRAW);
    }

    /** The binding point the rows block is bound at. */
    int binding() {
        return binding;
    }

    /** Starts a batch. */
    void clear() {
        count = 0;
    }

    /** Rows in the batch. */
    int count() {
        return count;
    }

    boolean full() {
        return count == DrawIdRewrite.ROWS;
    }

    /**
     * Adds a sub-draw: its row, {@code length} bytes (at most a row) of {@code source} from
     * {@code offset}, the rest zero; and, for a multi-draw, its index count and the byte offset of
     * its first index.
     */
    void add(ByteBuffer source, int offset, int length, int indexCount, int indexByteOffset) {
        int row = count * DrawIdRewrite.ROW_BYTES;
        for (int i = 0; i < DrawIdRewrite.ROW_BYTES; i += 4) {
            rows.putInt(row + i, i + 4 <= length ? source.getInt(offset + i) : 0);
        }
        counts.putInt(count * 4, indexCount);
        offsets.putInt(count * 4, indexByteOffset);
        count++;
    }

    /** Draws the batch's sub-draws in one multi-draw, reading 32-bit indices from the bound element buffer. */
    void multiDraw(JSObject extension, int mode) {
        JSObject views = intViews;
        if (views == null || !WebGl.multiDrawElements(extension, mode, views, UNSIGNED_INT, count)) {
            views = WebGl.intViews(Int8Array.fromJavaBuffer(counts), Int8Array.fromJavaBuffer(offsets));
            intViews = views;
            WebGl.multiDrawElements(extension, mode, views, UNSIGNED_INT, count);
        }
    }

    /** Uploads the batch and binds it as the block, its first row first. */
    void upload() {
        int bytes = count * DrawIdRewrite.ROW_BYTES;
        if (next + bytes > RING_BYTES) {
            // Rows overwritten now were read by draws already issued; WebGL keeps those draws'
            // results as they were.
            next = 0;
        }
        device.gl().bindBuffer(UNIFORM_BUFFER, ring);
        Int8Array view = rowsView;
        if (view == null || !WebGl.bufferSubData(device.gl(), UNIFORM_BUFFER, next, view, bytes)) {
            view = Int8Array.fromJavaBuffer(rows);
            rowsView = view;
            WebGl.bufferSubData(device.gl(), UNIFORM_BUFFER, next, view, bytes);
        }
        device.gl().bindBufferRange(UNIFORM_BUFFER, binding, ring, next, BLOCK_BYTES);
        next += (bytes + alignment - 1) / alignment * alignment;
    }
}
