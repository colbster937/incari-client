package incari.app.web.headless;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import org.joml.Vector4fc;

/**
 * Commands that do nothing, except where the game can see the result: buffer writes and copies
 * land in {@link HeadlessBuffer} memory, and read-back callbacks run at once.
 */
final class HeadlessEncoder implements CommandEncoder {

    private final HeadlessTransientMemory transientMemory = new HeadlessTransientMemory();

    @Override
    public void submit() {
        transientMemory.release();
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public RenderPass createRenderPass(RenderPassDescriptor descriptor) {
        return new HeadlessRenderPass();
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {}

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {}

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture,
            Vector4fc clearColor,
            GpuTexture depthTexture,
            double clearDepth,
            int regionX,
            int regionY,
            int regionWidth,
            int regionHeight,
            int mipLevel) {}

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {}

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        if (destination.buffer() instanceof HeadlessBuffer buffer) {
            buffer.write(destination.offset(), data);
        }
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        try (GpuBufferSlice.MappedView from = source.map(true, false)) {
            writeToBuffer(target, from.data());
        }
    }

    @Override
    public void writeToTexture(GpuTexture destination, NativeImage source) {}

    @Override
    public void writeToTexture(
            GpuTexture destination, NativeImage source, int mipLevel, int depthOrLayer, int destX, int destY) {}

    @Override
    public void writeToTexture(
            GpuTexture destination,
            ByteBuffer source,
            int mipLevel,
            int depthOrLayer,
            int destX,
            int destY,
            int width,
            int height) {}

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
            int arrayLayer) {}

    @Override
    public void copyTextureToBuffer(
            GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        callback.run();
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
            int height) {}

    @Override
    public GpuFence createFence() {
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
    public void writeTimestamp(GpuQueryPool pool, int index) {}
}
