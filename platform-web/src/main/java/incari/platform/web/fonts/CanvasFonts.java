package incari.platform.web.fonts;

import incari.platform.api.fonts.FontFace;
import incari.platform.api.fonts.PlatformFonts;
import org.jspecify.annotations.Nullable;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Smooth fonts drawn by the browser: the font file handed to the page as a {@code FontFace}, each
 * glyph drawn on a 2D canvas and its coverage read back. The browser has no FreeType for the game's
 * own TrueType provider, but it draws text natively, anti-aliased as it draws the page's own.
 *
 * <p>A font is loaded once per name: loading suspends (the browser parses it off the page's
 * thread), so the first {@link #open} must come from a background thread; opening it again at another
 * size doesn't suspend.
 */
public final class CanvasFonts implements PlatformFonts {

    @Override
    public @Nullable FontFace open(byte[] data, String name, float pixelsPerEm) {
        if (!loaded(name)) {
            String error = load(name, Int8Array.copyFromJavaArray(data));
            if (error != null) {
                return null;
            }
        }
        return new Face(name, pixelsPerEm);
    }

    private static final class Face implements FontFace {

        private final String css;

        Face(String name, float pixelsPerEm) {
            this.css = pixelsPerEm + "px \"" + name + "\"";
        }

        @Override
        public FontFace.@Nullable Glyph glyph(int codepoint) {
            JSObject drawn = draw(css, new String(Character.toChars(codepoint)));
            int width = width(drawn);
            int height = height(drawn);
            byte[] coverage = width > 0 && height > 0 ? coverage(drawn).copyToJavaArray() : new byte[0];
            return new FontFace.Glyph(advance(drawn), left(drawn), top(drawn), width, height, coverage);
        }

        @Override
        public void close() {}
    }

    @JSBody(params = "name", script = "var f = globalThis.incariFonts; return !!(f && f[name]);")
    private static native boolean loaded(String name);

    /** Loads the font into the page's fonts; null when it's ready, or why it couldn't be. */
    @Async
    private static native @Nullable String load(String name, Int8Array bytes);

    private static void load(String name, Int8Array bytes, AsyncCallback<@Nullable String> callback) {
        loadFont(name, bytes, () -> callback.complete(null), message -> callback.complete(message));
    }

    @JSFunctor
    interface Loaded extends JSObject {
        void loaded();
    }

    @JSFunctor
    interface Failed extends JSObject {
        void failed(String message);
    }

    @JSBody(
            params = {"name", "bytes", "loaded", "failed"},
            script =
                    "var face = new FontFace(name, bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));"
                            + " face.load().then(function(f) {"
                            + "  (typeof document !== 'undefined' ? document.fonts : self.fonts).add(f);"
                            + "  (globalThis.incariFonts = globalThis.incariFonts || {})[name] = true; loaded(); },"
                            + " function(e) { failed(String(e)); });")
    private static native void loadFont(String name, Int8Array bytes, Loaded loaded, Failed failed);

    /**
     * Draws one glyph white on a cleared canvas, its box from the browser's measurement padded a
     * pixel each side for the anti-aliasing, and returns its metrics and coverage (the canvas's
     * alpha channel). The canvas is made once and grown as glyphs need.
     */
    @JSBody(
            params = {"css", "text"},
            script = "var g = globalThis.incariGlyphs;"
                    + " if (!g) { var c = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(64, 64)"
                    + "  : document.createElement('canvas'); c.width = 64; c.height = 64;"
                    + "  g = globalThis.incariGlyphs = { canvas: c, ctx: c.getContext('2d', { willReadFrequently: true }) }; }"
                    + " var ctx = g.ctx; ctx.font = css; var m = ctx.measureText(text);"
                    + " var x0 = Math.floor(-m.actualBoundingBoxLeft) - 1, x1 = Math.ceil(m.actualBoundingBoxRight) + 1;"
                    + " var up = Math.ceil(m.actualBoundingBoxAscent) + 1, down = Math.ceil(m.actualBoundingBoxDescent) + 1;"
                    + " var w = Math.max(0, x1 - x0), h = Math.max(0, up + down);"
                    + " var r = { advance: m.width, left: x0, top: up, width: w, height: h, alpha: null };"
                    + " if (w === 0 || h === 0 || /^\\s$/.test(text)) { r.width = 0; r.height = 0; return r; }"
                    + " if (g.canvas.width < w || g.canvas.height < h) {"
                    + "  g.canvas.width = Math.max(g.canvas.width, w); g.canvas.height = Math.max(g.canvas.height, h);"
                    + "  ctx = g.ctx; ctx.font = css; }"
                    + " ctx.clearRect(0, 0, w, h); ctx.fillStyle = '#fff'; ctx.textBaseline = 'alphabetic';"
                    + " ctx.fillText(text, -x0, up);"
                    + " var px = ctx.getImageData(0, 0, w, h).data; var a = new Int8Array(w * h);"
                    + " for (var i = 0; i < a.length; i++) a[i] = px[i * 4 + 3];"
                    + " r.alpha = a; return r;")
    private static native JSObject draw(String css, String text);

    @JSBody(params = "r", script = "return r.advance;")
    private static native float advance(JSObject r);

    @JSBody(params = "r", script = "return r.left;")
    private static native int left(JSObject r);

    @JSBody(params = "r", script = "return r.top;")
    private static native int top(JSObject r);

    @JSBody(params = "r", script = "return r.width;")
    private static native int width(JSObject r);

    @JSBody(params = "r", script = "return r.height;")
    private static native int height(JSObject r);

    @JSBody(params = "r", script = "return r.alpha;")
    private static native Int8Array coverage(JSObject r);
}
