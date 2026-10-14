package incari.render.webgl2;

import java.util.ArrayList;
import java.util.List;

/**
 * Uniform buffers written since the last draw, uploaded together before the next one (ADR 0027).
 *
 * <p>The game writes most uniforms by mapping a block of a ring buffer, writing it and closing the
 * view: its dynamic transforms take one such block per draw. Closing a view used to upload its
 * bytes at once, a {@code bufferSubData} per block, each a call from WebAssembly into JavaScript
 * that the browser checks and copies into its command buffer. The writes follow each other in the
 * ring, so a buffer instead notes the range written ({@link WebGl2Buffer#deferUpload}) and the
 * render pass uploads that whole range in one call when it next draws ({@link #flush}).
 *
 * <p>Nothing else needs the bytes before a draw: the game's copy, which this leaves current, is
 * what the CPU side reads (texel buffers, copies, read-backs), and a uniform buffer always keeps
 * one ({@link BufferCopies}). Hot path: no allocation once the list has grown.
 */
final class PendingUploads {

    /** Defer at all; the page's {@code ?uniforms=each} uploads each write on its own, to compare. */
    static volatile boolean enabled = true;

    private final List<WebGl2Buffer> buffers = new ArrayList<>();

    /** {@code buffer} has a range to upload; called once until the next {@link #flush}. */
    void add(WebGl2Buffer buffer) {
        buffers.add(buffer);
    }

    /** Uploads every pending range: before a draw reads the buffers. */
    void flush() {
        if (buffers.isEmpty()) {
            return;
        }
        for (int i = 0; i < buffers.size(); i++) {
            buffers.get(i).flushUpload();
        }
        buffers.clear();
    }
}
