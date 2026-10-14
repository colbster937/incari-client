package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.COLOR_BUFFER_BIT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DRAW_FRAMEBUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.READ_FRAMEBUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.SCISSOR_TEST;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * The canvas. The game renders into its own target and blits it here once a frame; the browser
 * shows the canvas when the frame's JavaScript task ends (the game yields to the page every
 * frame), so presenting does nothing. The canvas's size belongs to the platform
 * ({@code platform-web}'s window), which keeps it at the CSS size times the pixel ratio.
 */
final class WebGl2Surface implements GpuSurfaceBackend {

    private final WebGl2Device device;
    private GpuSurface.Configuration configuration = new GpuSurface.Configuration(1, 1, GpuSurface.PresentMode.FIFO);

    WebGl2Surface(WebGl2Device device) {
        this.device = device;
    }

    @Override
    public void configure(GpuSurface.Configuration config) {
        configuration = config;
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (WebGl.isContextLost(device.gl())) {
            // Restoring a lost context (the GPU process crashed, or the tab was starved) isn't done yet.
            throw new SurfaceException("The WebGL context was lost");
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        WebGl2TextureView view = (WebGl2TextureView) textureView;
        WebGL2RenderingContext gl = device.gl();
        device.framebuffers().bindSingle(READ_FRAMEBUFFER, view.glTexture(), view.baseMipLevel());
        gl.bindFramebuffer(DRAW_FRAMEBUFFER, null);
        gl.disable(SCISSOR_TEST);
        device.state().colorMask(15);
        int width = view.getWidth(0);
        int height = view.getHeight(0);
        int targetWidth = configuration.width();
        int targetHeight = configuration.height();
        boolean sameSize = width == targetWidth && height == targetHeight;
        gl.blitFramebuffer(
                0, 0, width, height, 0, 0, targetWidth, targetHeight, COLOR_BUFFER_BIT, sameSize ? NEAREST : LINEAR);
        device.invalidateBindings();
        device.frames++;
        device.frameEnded();
        WebGl.publishStats(device.frames, device.draws, device.skippedDraws, width, height, targetWidth, targetHeight);
    }

    @Override
    public void present() {}

    /** Only FIFO: the browser shows frames on its own vsync, whatever the game asks. */
    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO);
    }

    @Override
    public void close() {}
}
