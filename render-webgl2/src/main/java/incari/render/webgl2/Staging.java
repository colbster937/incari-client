package incari.render.webgl2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * Carries bytes from the game to WebGL. Under WebAssembly GC a Java {@code byte[]} lives in the GC
 * heap, where JavaScript can't see it; only a direct buffer, which TeaVM keeps in the module's
 * linear memory, can be read by WebGL. The game's off-heap memory is in linear memory (ADR 0008),
 * so its buffers are read where they are; anything else (a heap buffer, or pixels not on
 * their element size) is copied into one direct buffer and read there.
 *
 * <p>Uploads read through views of the whole linear memory, kept from one upload to the next, with
 * the source's address as WebGL2's source offset (ADR 0027). Making a view per upload made a
 * JavaScript object per upload, which TeaVM's interop wraps and keeps in a weak map; thousands of
 * those a frame made major garbage collections pause for tens of milliseconds walking the map.
 * Hot path: no allocation, Java or JavaScript, once the copy buffer has grown to the largest copy.
 */
final class Staging {

    private ByteBuffer direct = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);

    /** {@link WebGl#memoryViews}: made on first use, and again when growing the memory detaches them. */
    private @Nullable JSObject memoryViews;

    /**
     * Uploads {@code length} bytes of {@code source} from {@code offset} to the buffer bound at
     * {@code target}, at {@code destination}.
     */
    void bufferSubData(
            WebGL2RenderingContext gl, int target, int destination, ByteBuffer source, int offset, int length) {
        int address = address(source, offset, length);
        if (!WebGl.bufferSubDataAt(gl, target, destination, views(false), address, length)) {
            WebGl.bufferSubDataAt(gl, target, destination, views(true), address, length);
        }
    }

    /**
     * {@code texSubImage2D} with {@code length} bytes of {@code source} from {@code offset} as its
     * pixels, of pixel {@code type}. The unpack parameters apply from there, as they would to a
     * typed array starting at that byte.
     */
    void texSubImage2D(
            WebGL2RenderingContext gl,
            int target,
            int level,
            int x,
            int y,
            int width,
            int height,
            int format,
            int type,
            ByteBuffer source,
            int offset,
            int length) {
        int address = address(source, offset, length);
        int done = WebGl.texSubImage2DAt(gl, target, level, x, y, width, height, format, type, views(false), address);
        if (done == 0) {
            done = WebGl.texSubImage2DAt(gl, target, level, x, y, width, height, format, type, views(true), address);
        }
        if (done == 2) {
            // Not on the element size: copied to the start of the copy buffer, which is.
            int aligned = copyToDirect(source, offset, length);
            if (WebGl.texSubImage2DAt(gl, target, level, x, y, width, height, format, type, views(false), aligned)
                    == 0) {
                WebGl.texSubImage2DAt(gl, target, level, x, y, width, height, format, type, views(true), aligned);
            }
        }
    }

    /**
     * A view to read pixels of {@code type} into ({@code readPixels}); copy them out with
     * {@link #readBack} before the next call. Rare (screenshots, map exports): a view per call.
     */
    ArrayBufferView scratch(int length, int type) {
        ensureCapacity(length);
        return WebGl.typed(Int8Array.fromJavaBuffer(direct.slice(0, length)), type);
    }

    /** Copies what {@link #scratch} received into {@code target} at {@code offset}. */
    void readBack(ByteBuffer target, int offset, int length) {
        target.put(offset, direct, 0, length);
    }

    /** The linear-memory address of {@code source}'s byte {@code offset}; a heap buffer is copied first. */
    private int address(ByteBuffer source, int offset, int length) {
        if (!source.isDirect()) {
            return copyToDirect(source, offset, length);
        }
        return Math.toIntExact(MemoryUtil.memAddress(source) - source.position() + offset);
    }

    /** Copies the bytes into the start of the copy buffer; its address. */
    private int copyToDirect(ByteBuffer source, int offset, int length) {
        ensureCapacity(length);
        if (source.hasArray()) {
            direct.put(0, source.array(), source.arrayOffset() + offset, length);
        } else {
            direct.put(0, source, offset, length);
        }
        return Math.toIntExact(MemoryUtil.memAddress(direct) - direct.position());
    }

    private JSObject views(boolean remake) {
        JSObject views = memoryViews;
        if (views == null || remake) {
            // Any view of linear memory leads to its buffer; the copy buffer's is at hand.
            views = WebGl.memoryViews(Int8Array.fromJavaBuffer(direct));
            memoryViews = views;
        }
        return views;
    }

    private void ensureCapacity(int length) {
        if (direct.capacity() < length) {
            int grown = Math.max(length, direct.capacity() * 2);
            direct = ByteBuffer.allocateDirect(grown).order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
