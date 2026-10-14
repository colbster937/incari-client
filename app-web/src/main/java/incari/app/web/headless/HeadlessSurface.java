package incari.app.web.headless;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A surface that never has an image to draw into: acquiring one does nothing, so the game skips
 * its blit and present each frame.
 */
final class HeadlessSurface implements GpuSurface {

    private GpuSurface.@Nullable Configuration configuration;

    @Override
    public void configure(GpuSurface.Configuration config) {
        configuration = config;
    }

    @Override
    public Optional<GpuSurface.Configuration> currentConfiguration() {
        return Optional.ofNullable(configuration);
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public boolean isAcquired() {
        return false;
    }

    @Override
    public void acquireNextTexture() {}

    @Override
    public void blitFromTexture(CommandEncoder commandEncoder, GpuTextureView textureView) {}

    @Override
    public void present() {}

    @Override
    public void close() {}
}
