package incari.render.webgl2;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import incari.render.webgl2.shaders.ShaderManifest;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * The browser's graphics backend: a WebGL2 context on the page's canvas, under the game's own
 * frontend device. The browser build's entry point installs it with the canvas the platform made
 * and the shaders the build translated.
 */
public final class WebGl2Backend implements GpuBackend {

    private final String canvasId;
    private final ShaderManifest shaders;
    private final boolean eagerShaders;
    private final boolean multiDraw;

    /**
     * Whether every buffer keeps the game's copy of its contents, as before ADR 0025, instead
     * of large vertex-only buffers keeping none: the page's {@code ?copies=all}, to compare.
     */
    public static void keepAllCopies(boolean all) {
        BufferCopies.keepAll = all;
    }

    /**
     * Whether each write to a uniform buffer is uploaded when its view closes, as before ADR 0027,
     * instead of each buffer's writes together before the next draw: the page's {@code ?uniforms=each},
     * to compare.
     */
    public static void uploadEachUniformWrite(boolean each) {
        PendingUploads.enabled = !each;
    }

    /**
     * Whether draws from the game's sequential quad indices move the vertex attributes to their
     * base vertex, as before ADR 0027, instead of reading the backend's own quad indices from
     * there: the page's {@code ?rebase=off}, to compare.
     */
    public static void movePointersForQuadDraws(boolean move) {
        WebGl2Device.rebaseQuadDraws = !move;
    }

    /** The page's {@code ?drawcensus}: logs each pipeline's draws a frame every 600 frames. */
    public static void countDrawsByPipeline(boolean on) {
        WebGl2Device.census = on;
    }

    /** The page's {@code ?gpucensus}: logs each pipeline's GPU time a frame every 600 frames. */
    public static void timeDrawsByPipeline(boolean on) {
        WebGl2Device.timeDraws = on;
    }

    /**
     * @param canvasId the page's canvas, which the platform owns and sizes
     * @param shaders the translated shaders ({@code shaders.json})
     * @param eagerShaders link every program when the game compiles its pipelines, instead of on
     *     first use: slower to start, but every shader is checked by the browser's compiler at once
     * @param multiDraw draw terrain with WEBGL_multi_draw where the browser has it; off only
     *     to compare against the draw per section
     */
    public WebGl2Backend(String canvasId, ShaderManifest shaders, boolean eagerShaders, boolean multiDraw) {
        this.canvasId = canvasId;
        this.shaders = shaders;
        this.eagerShaders = eagerShaders;
        this.multiDraw = multiDraw;
    }

    @Override
    public String getName() {
        return "WebGL2";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (!WebGl.isSupported()) {
            throw new BackendCreationException(
                    "This browser has no WebGL2", BackendCreationException.Reason.OPENGL_MISSING);
        }
    }

    @Override
    public void unloadLibrary() {}

    /** The canvas is the window; the handle only has to be non-zero. */
    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        return 1L;
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        WebGL2RenderingContext gl = WebGl.context(canvasId);
        if (gl == null) {
            throw new BackendCreationException(
                    "The browser didn't make a WebGL2 context (is hardware acceleration off?)",
                    BackendCreationException.Reason.OPENGL_MISSING);
        }
        return new FrontendGpuDevice(new WebGl2Device(gl, shaders, eagerShaders, multiDraw));
    }
}
