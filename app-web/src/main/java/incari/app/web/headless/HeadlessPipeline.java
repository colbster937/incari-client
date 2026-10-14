package incari.app.web.headless;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;

/** A pipeline that "compiled" at once: there are no shaders to run. */
final class HeadlessPipeline implements CompiledRenderPipeline {

    private boolean closed;

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
