package incari.app.web.headless;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/** A render pass that draws nothing. */
final class HeadlessRenderPass implements RenderPass {

    @Override
    public void pushDebugGroup(java.util.function.Supplier<String> label) {}

    @Override
    public void popDebugGroup() {}

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {}

    @Override
    public void setPipeline(CompiledRenderPipeline pipeline) {}

    @Override
    public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {}

    @Override
    public void setUniform(String name, GpuBuffer value) {}

    @Override
    public void setUniform(String name, GpuBufferSlice value) {}

    @Override
    public void pushConstants(ByteBuffer value) {}

    @Override
    public void enableScissor(int x, int y, int width, int height) {}

    @Override
    public void disableScissor() {}

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {}

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {}

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {}

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {}

    @Override
    public void multiDrawIndexed(
            PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {}

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {}

    @Override
    public <T> void drawMultipleIndexed(
            Collection<RenderPass.Draw<T>> draws,
            @Nullable GpuBuffer defaultIndexBuffer,
            @Nullable IndexType defaultIndexType,
            Collection<String> dynamicUniforms,
            T uniformArgument) {}

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {}

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {}

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {}

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {}

    @Override
    public void close() {}
}
