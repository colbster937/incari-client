package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.COLOR_ATTACHMENT0;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DRAW_FRAMEBUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.FRAMEBUFFER_COMPLETE;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NONE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLFramebuffer;

/**
 * Framebuffer objects by the textures and levels attached to them. The game names its render
 * targets per pass, and WebGL needs a framebuffer object for each combination; making one per pass
 * would allocate and validate every frame, so they're kept until one of their textures closes.
 */
final class Framebuffers {

    /** One attachment: a texture's mip level. Textures compare by identity. */
    record Attachment(WebGl2Texture texture, int level) {
        static @Nullable Attachment of(@Nullable WebGl2TextureView view) {
            return view == null ? null : new Attachment(view.glTexture(), view.baseMipLevel());
        }
    }

    private record Key(
            List<@Nullable Attachment> colors, @Nullable Attachment depth) {}

    /**
     * A framebuffer object, and whether its draw buffers are set. They're the framebuffer's own
     * state and follow from its attachments, so they're set once, the first time it's bound to
     * draw into: setting them on every bind was 3% of the fly-through on the floor stand-in.
     */
    private static final class Entry {
        final WebGLFramebuffer framebuffer;
        boolean drawBuffersSet;

        Entry(WebGLFramebuffer framebuffer) {
            this.framebuffer = framebuffer;
        }
    }

    private final WebGl2Device device;
    private final Map<Key, Entry> framebuffers = new HashMap<>();

    Framebuffers(WebGl2Device device) {
        this.device = device;
    }

    /**
     * Binds the framebuffer with these attachments to {@code target} (draw or read), making it the
     * first time. For a draw framebuffer, every color attachment is also a draw buffer.
     */
    void bind(int target, List<@Nullable Attachment> colors, @Nullable Attachment depth) {
        WebGL2RenderingContext gl = device.gl();
        Key key = new Key(colors, depth);
        Entry entry = framebuffers.get(key);
        if (entry == null) {
            WebGLFramebuffer framebuffer = gl.createFramebuffer();
            gl.bindFramebuffer(target, framebuffer);
            for (int i = 0; i < colors.size(); i++) {
                Attachment color = colors.get(i);
                if (color != null) {
                    gl.framebufferTexture2D(
                            target,
                            COLOR_ATTACHMENT0 + i,
                            color.texture().imageTarget(0),
                            color.texture().handle(),
                            color.level());
                }
            }
            if (depth != null) {
                gl.framebufferTexture2D(
                        target,
                        GlFormats.attachment(depth.texture().getFormat()),
                        depth.texture().imageTarget(0),
                        depth.texture().handle(),
                        depth.level());
            }
            int status = gl.checkFramebufferStatus(target);
            if (status != FRAMEBUFFER_COMPLETE) {
                WebGl.error("[Incari] Framebuffer for " + describe(key) + " is incomplete (status 0x"
                        + Integer.toHexString(status) + ")");
            }
            entry = new Entry(framebuffer);
            framebuffers.put(key, entry);
        } else {
            gl.bindFramebuffer(target, entry.framebuffer);
        }
        if (target == DRAW_FRAMEBUFFER && !entry.drawBuffersSet) {
            entry.drawBuffersSet = true;
            int[] drawBuffers = new int[colors.size()];
            for (int i = 0; i < drawBuffers.length; i++) {
                drawBuffers[i] = colors.get(i) != null ? COLOR_ATTACHMENT0 + i : NONE;
            }
            gl.drawBuffers(drawBuffers);
        }
    }

    /** Binds a framebuffer with one attachment, to read from or clear. */
    void bindSingle(int target, WebGl2Texture texture, int level) {
        Attachment attachment = new Attachment(texture, level);
        if (texture.getFormat().hasColorAspect()) {
            List<@Nullable Attachment> colors = new ArrayList<>(1);
            colors.add(attachment);
            bind(target, colors, null);
        } else {
            bind(target, List.of(), attachment);
        }
    }

    /** Deletes every framebuffer {@code texture} is attached to; it's closing. */
    void forget(WebGl2Texture texture) {
        Iterator<Map.Entry<Key, Entry>> entries = framebuffers.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Entry> entry = entries.next();
            if (uses(entry.getKey(), texture)) {
                device.gl().deleteFramebuffer(entry.getValue().framebuffer);
                entries.remove();
            }
        }
    }

    private static boolean uses(Key key, WebGl2Texture texture) {
        Attachment depth = key.depth();
        if (depth != null && depth.texture() == texture) {
            return true;
        }
        for (Attachment color : key.colors()) {
            if (color != null && color.texture() == texture) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Key key) {
        StringBuilder text = new StringBuilder();
        for (Attachment color : key.colors()) {
            text.append(
                            color == null
                                    ? "(none)"
                                    : color.texture().getLabel() + " "
                                            + color.texture().getFormat())
                    .append(", ");
        }
        Attachment depth = key.depth();
        text.append("depth ")
                .append(
                        depth == null
                                ? "(none)"
                                : depth.texture().getLabel() + " "
                                        + depth.texture().getFormat());
        return text.toString();
    }
}
