package incari.client.render;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A graphics backend that compiles pipelines itself. Mojang's frontend device
 * ({@code FrontendGpuDevice}) normally compiles every pipeline through shaderc and SPIRV-Cross,
 * which are native libraries; the browser has neither, so its WebGL2 backend brings shaders the
 * build already translated. A backend implementing this gets the frontend's pipeline requests
 * directly, and the frontend never creates its shaderc compiler.
 */
public interface PipelineCompilingBackend {

    /**
     * Compiles {@code pipeline}, as {@code GpuDevice.compilePipeline} does. The result must be a
     * pipeline the frontend's render passes accept ({@code FrontendRenderPipeline}); a pipeline that
     * can't be compiled is {@link CompiledRenderPipeline.Pending#NULL}.
     */
    CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(
            RenderPipeline pipeline, ShaderSource shaderSource, Executor executor);
}
