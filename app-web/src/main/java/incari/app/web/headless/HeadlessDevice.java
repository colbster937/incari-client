package incari.app.web.headless;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A device with no GPU behind it: textures and samplers are descriptions, buffers are plain memory
 * (so mapped writes and reads behave), pipelines "compile" at once, and commands do nothing.
 */
final class HeadlessDevice implements GpuDevice {

    private static final DeviceInfo INFO = new DeviceInfo(
            "Headless",
            "Incari",
            "none",
            false,
            "Headless",
            1.0F,
            new DeviceLimits(1, 256, 8192, 256L * 1024 * 1024, 0, 8, 0),
            new DeviceFeatures(false, false, false, false, false, false, false, false),
            Set.of(),
            new HintsAndWorkarounds(false, false, false, false),
            DeviceType.CPU);

    private final CommandEncoder encoder = new HeadlessEncoder();

    @Override
    public GpuSurface createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new HeadlessSurface();
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        return encoder;
    }

    @Override
    public GpuSampler createSampler(
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod) {
        return new HeadlessSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(
            @Nullable Supplier<String> label,
            @GpuTexture.Usage int usage,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        return createTexture(
                label == null ? null : label.get(), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(
            @Nullable String label,
            @GpuTexture.Usage int usage,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        return new HeadlessTexture(label == null ? "" : label, usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture) {
        return new HeadlessTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new HeadlessTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        return new HeadlessBuffer(usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        HeadlessBuffer buffer = new HeadlessBuffer(usage, data.remaining());
        buffer.write(0, data);
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(
            RenderPipeline pipeline, ShaderSource shaderSource, Executor executor) {
        CompiledRenderPipeline compiled = new HeadlessPipeline();
        return CompletableFuture.completedFuture(() -> compiled);
    }

    @Override
    public void close() {}

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new HeadlessQueryPool(size);
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return INFO;
    }
}
