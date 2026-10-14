package incari.app.web.headless;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.jspecify.annotations.Nullable;

/**
 * A graphics backend that draws nothing, for running the game before the browser has a renderer
 * (as before the WebGL2 backend existed). Its device accepts every call and keeps no GPU state;
 * the entry point also switches the frame's render step off, so the game only ticks.
 */
public final class HeadlessBackend implements GpuBackend {

    @Override
    public String getName() {
        return "Headless";
    }

    @Override
    public void loadLibrary() {}

    @Override
    public void unloadLibrary() {}

    /** The canvas is the window; the handle only has to be non-zero. */
    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        return 1L;
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) {
        return new HeadlessDevice();
    }
}
