package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.COLOR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.COLOR_BUFFER_BIT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DEPTH;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DEPTH_BUFFER_BIT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.DRAW_FRAMEBUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.PACK_ALIGNMENT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.READ_FRAMEBUFFER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.SCISSOR_TEST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNPACK_ALIGNMENT;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNPACK_ROW_LENGTH;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNPACK_SKIP_PIXELS;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNPACK_SKIP_ROWS;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * Commands run as they're recorded: WebGL is an immediate API with one queue, so there's nothing
 * to batch until submit, which only recycles the frame's transient memory. The game's frontend
 * checks every argument before it gets here.
 *
 * <p>Copies that read from the GPU ({@code copyTextureToBuffer}) block on {@code readPixels}. The
 * game uses them for screenshots and map exports, not every frame; making them asynchronous
 * (pixel pack buffers and a fence) is left for later.
 */
final class WebGl2CommandEncoder implements CommandEncoderBackend {

    private final WebGl2Device device;
    private final WebGl2TransientMemory transientMemory;
    private final float[] clearColor = new float[4];
    private final float[] clearDepth = new float[1];

    WebGl2CommandEncoder(WebGl2Device device) {
        this.device = device;
        this.transientMemory = new WebGl2TransientMemory(device);
    }

    @Override
    public void submit() {
        transientMemory.release();
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        device.gpuTimer().frameWork();
        WebGL2RenderingContext gl = device.gl();
        List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        List<Framebuffers.@Nullable Attachment> colorAttachments = new ArrayList<>(colors.size());
        int width = 0;
        int height = 0;
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> color : colors) {
            WebGl2TextureView view = color == null ? null : (WebGl2TextureView) color.textureView();
            colorAttachments.add(Framebuffers.Attachment.of(view));
            if (view != null) {
                width = view.getWidth(0);
                height = view.getHeight(0);
            }
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        WebGl2TextureView depthView = depth == null ? null : (WebGl2TextureView) depth.textureView();
        if (depthView != null && width == 0) {
            width = depthView.getWidth(0);
            height = depthView.getHeight(0);
        }
        device.framebuffers().bind(DRAW_FRAMEBUFFER, colorAttachments, Framebuffers.Attachment.of(depthView));

        // Clears and draws are limited to the render area; the viewport covers the whole target.
        var area = descriptor.renderArea();
        gl.enable(SCISSOR_TEST);
        gl.scissor(area.x(), area.y(), area.width(), area.height());
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> color = colors.get(i);
            if (color != null && color.clearValue().isPresent()) {
                device.state().colorMask(15);
                gl.clearBufferfv(COLOR, i, rgba(color.clearValue().get()));
            }
        }
        if (depth != null && depth.clearValue().isPresent()) {
            device.state().depthMask(true);
            gl.clearBufferfv(DEPTH, 0, depth((float) depth.clearValue().getAsDouble()));
        }
        gl.viewport(0, 0, width, height);
        return new WebGl2RenderPass(device, area);
    }

    @Override
    public void submitRenderPass() {
        // Nothing to end: the pass's commands ran as they came.
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc color) {
        device.gpuTimer().frameWork();
        WebGl2Texture texture = (WebGl2Texture) colorTexture;
        for (int level = 0; level < texture.getMipLevels(); level++) {
            device.framebuffers().bindSingle(DRAW_FRAMEBUFFER, texture, level);
            prepareFullClear(true, false);
            device.gl().clearBufferfv(COLOR, 0, rgba(color));
        }
    }

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture, double clearDepth) {
        clearColorTexture(colorTexture, color);
        clearDepthTexture(depthTexture, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture,
            Vector4fc color,
            GpuTexture depthTexture,
            double clearDepth,
            int regionX,
            int regionY,
            int regionWidth,
            int regionHeight,
            int mipLevel) {
        device.gpuTimer().frameWork();
        WebGL2RenderingContext gl = device.gl();
        List<Framebuffers.@Nullable Attachment> colors = new ArrayList<>(1);
        colors.add(new Framebuffers.Attachment((WebGl2Texture) colorTexture, mipLevel));
        device.framebuffers()
                .bind(DRAW_FRAMEBUFFER, colors, new Framebuffers.Attachment((WebGl2Texture) depthTexture, mipLevel));
        device.state().colorMask(15);
        device.state().depthMask(true);
        gl.enable(SCISSOR_TEST);
        gl.scissor(regionX, regionY, regionWidth, regionHeight);
        gl.clearBufferfv(COLOR, 0, rgba(color));
        gl.clearBufferfv(DEPTH, 0, depth((float) clearDepth));
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        device.gpuTimer().frameWork();
        WebGl2Texture texture = (WebGl2Texture) depthTexture;
        for (int level = 0; level < texture.getMipLevels(); level++) {
            device.framebuffers().bindSingle(DRAW_FRAMEBUFFER, texture, level);
            prepareFullClear(false, true);
            device.gl().clearBufferfv(DEPTH, 0, depth((float) clearDepth));
        }
    }

    private void prepareFullClear(boolean color, boolean depth) {
        device.gl().disable(SCISSOR_TEST);
        if (color) {
            device.state().colorMask(15);
        }
        if (depth) {
            device.state().depthMask(true);
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        ((WebGl2Buffer) destination.buffer()).write(Math.toIntExact(destination.offset()), data);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        // The game's copy of a buffer that keeps one is current: the copy happens there and is
        // uploaded (or uploaded straight from it, into a buffer that keeps none).
        WebGl2Buffer from = (WebGl2Buffer) source.buffer();
        WebGl2Buffer to = (WebGl2Buffer) target.buffer();
        to.copyFrom(
                Math.toIntExact(target.offset()),
                from,
                Math.toIntExact(source.offset()),
                Math.toIntExact(source.length()));
    }

    @Override
    public void writeToTexture(
            GpuTexture destination,
            ByteBuffer source,
            int mipLevel,
            int depthOrLayer,
            int destX,
            int destY,
            int width,
            int height) {
        WebGl2Texture texture = (WebGl2Texture) destination;
        GlFormats.TextureFormat format = texture.glFormat();
        WebGL2RenderingContext gl = device.gl();
        device.bindTextureForUpdate(texture.target(), texture.handle());
        int length = width * height * destination.getFormat().blockSize();
        setUnpack(1, 0, 0, 0);
        device.staging()
                .texSubImage2D(
                        gl,
                        texture.imageTarget(depthOrLayer),
                        mipLevel,
                        destX,
                        destY,
                        width,
                        height,
                        format.format(),
                        format.type(),
                        source,
                        source.position(),
                        length);
    }

    @Override
    public void copyBufferToTexture(
            GpuBufferSlice source,
            int sourceX,
            int sourceY,
            int sourceWidth,
            int sourceHeight,
            GpuTexture destination,
            int destinationX,
            int destinationY,
            int copyWidth,
            int copyHeight,
            int mipLevel,
            int arrayLayer) {
        WebGl2Texture texture = (WebGl2Texture) destination;
        GlFormats.TextureFormat format = texture.glFormat();
        WebGl2Buffer buffer = (WebGl2Buffer) source.buffer();
        int blockSize = destination.getFormat().blockSize();
        // Only the rows the copy reads, from its first row; the unpack skips pick the rectangle out.
        int firstByte = Math.toIntExact(source.offset()) + sourceY * sourceWidth * blockSize;
        int length = (copyHeight - 1) * sourceWidth * blockSize + (sourceX + copyWidth) * blockSize;
        device.bindTextureForUpdate(texture.target(), texture.handle());
        setUnpack(1, sourceWidth, sourceX, 0);
        device.staging()
                .texSubImage2D(
                        device.gl(),
                        texture.imageTarget(arrayLayer),
                        mipLevel,
                        destinationX,
                        destinationY,
                        copyWidth,
                        copyHeight,
                        format.format(),
                        format.type(),
                        buffer.memory(),
                        firstByte,
                        length);
        setUnpack(1, 0, 0, 0);
    }

    private void setUnpack(int alignment, int rowLength, int skipPixels, int skipRows) {
        WebGL2RenderingContext gl = device.gl();
        gl.pixelStorei(UNPACK_ALIGNMENT, alignment);
        gl.pixelStorei(UNPACK_ROW_LENGTH, rowLength);
        gl.pixelStorei(UNPACK_SKIP_PIXELS, skipPixels);
        gl.pixelStorei(UNPACK_SKIP_ROWS, skipRows);
    }

    @Override
    public void copyTextureToBuffer(
            GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        copyTextureToBuffer(
                source,
                destination,
                offset,
                callback,
                mipLevel,
                0,
                0,
                source.getWidth(mipLevel),
                source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            GpuTexture source,
            GpuBuffer destination,
            long offset,
            Runnable callback,
            int mipLevel,
            int x,
            int y,
            int width,
            int height) {
        WebGl2Texture texture = (WebGl2Texture) source;
        GlFormats.TextureFormat format = texture.glFormat();
        WebGl2Buffer buffer = (WebGl2Buffer) destination;
        int length = width * height * source.getFormat().blockSize();
        device.framebuffers().bindSingle(READ_FRAMEBUFFER, texture, mipLevel);
        device.gl().pixelStorei(PACK_ALIGNMENT, 1);
        device.gl()
                .readPixels(
                        x,
                        y,
                        width,
                        height,
                        format.format(),
                        format.type(),
                        device.staging().scratch(length, format.type()));
        device.staging().readBack(buffer.memory(), Math.toIntExact(offset), length);
        buffer.upload(Math.toIntExact(offset), length);
        // readPixels waited for the GPU, so the data is there now.
        callback.run();
    }

    @Override
    public void copyTextureToTexture(
            GpuTexture source,
            GpuTexture destination,
            int mipLevel,
            int destX,
            int destY,
            int sourceX,
            int sourceY,
            int width,
            int height) {
        WebGL2RenderingContext gl = device.gl();
        device.framebuffers().bindSingle(READ_FRAMEBUFFER, (WebGl2Texture) source, mipLevel);
        device.framebuffers().bindSingle(DRAW_FRAMEBUFFER, (WebGl2Texture) destination, mipLevel);
        gl.disable(SCISSOR_TEST);
        int mask = source.getFormat().hasColorAspect() ? COLOR_BUFFER_BIT : DEPTH_BUFFER_BIT;
        gl.blitFramebuffer(
                sourceX,
                sourceY,
                sourceX + width,
                sourceY + height,
                destX,
                destY,
                destX + width,
                destY + height,
                mask,
                NEAREST);
    }

    @Override
    public GpuFence createFence() {
        // Every command has run by the time the game could wait: see readPixels above.
        return new GpuFence() {
            @Override
            public void close() {}

            @Override
            public boolean awaitCompletion(long timeoutNS) {
                return true;
            }
        };
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        // WebGL2's timer queries (EXT_disjoint_timer_query_webgl2) are off in most browsers.
    }

    private float[] depth(float value) {
        clearDepth[0] = value;
        return clearDepth;
    }

    private float[] rgba(Vector4fc color) {
        clearColor[0] = color.x();
        clearColor[1] = color.y();
        clearColor[2] = color.z();
        clearColor[3] = color.w();
        return clearColor;
    }
}
