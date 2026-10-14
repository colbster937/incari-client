package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER;

import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGLBuffer;

/**
 * The backend's own index buffer of sequential quads (0 1 2 2 3 0, 4 5 6 6 7 4, ...), 32-bit, as
 * long as the largest vertex heap drawn from needs (ADR 0022). WebGL2 has no base vertex, so
 * where the game draws a terrain section from vertex {@code v} of a shared buffer with the start
 * of its own quad index buffer, this buffer is read from quad {@code v / 4} instead: the indices
 * there already count from {@code v}. That makes every section of a heap drawable by one
 * multi-draw.
 *
 * <p>Grows by doubling, so it's re-uploaded a handful of times as the world loads; each index
 * costs 4 bytes of GPU memory, 6 per quad of the largest heap.
 */
final class QuadIndices {

    /** Enough for 16384 quads before the first growth. */
    private static final int MIN_INDICES = 6 * 16384;

    private final WebGl2Device device;
    private @Nullable WebGLBuffer buffer;
    private int indices;

    QuadIndices(WebGl2Device device) {
        this.device = device;
    }

    /** Binds the buffer as the element array, grown to hold at least {@code needed} indices. */
    void bind(long needed) {
        if (needed > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("A quad index buffer of " + needed + " indices is too large");
        }
        WebGLBuffer current = buffer;
        if (current == null || needed > indices) {
            int size = Math.max(MIN_INDICES, indices);
            while (size < needed) {
                size = (int) Math.min((long) size * 2, Integer.MAX_VALUE / 4 / 6 * 6);
            }
            if (current == null) {
                current = device.gl().createBuffer();
                buffer = current;
            }
            device.gl().bindBuffer(ELEMENT_ARRAY_BUFFER, current);
            WebGl.uploadQuadIndices(device.gl(), size);
            indices = size;
            return;
        }
        device.gl().bindBuffer(ELEMENT_ARRAY_BUFFER, current);
    }

    /** Indices the buffer holds, for tests and the log. */
    int size() {
        return indices;
    }
}
