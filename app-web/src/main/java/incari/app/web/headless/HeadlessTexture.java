package incari.app.web.headless;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;

/** A texture's description with no pixels: writes to it are dropped. */
final class HeadlessTexture implements GpuTexture {

    private final String label;
    private final @GpuTexture.Usage int usage;
    private final GpuFormat format;
    private final int width;
    private final int height;
    private final int depthOrLayers;
    private final int mipLevels;
    private boolean closed;

    HeadlessTexture(
            String label,
            @GpuTexture.Usage int usage,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        this.label = label;
        this.usage = usage;
        this.format = format;
        this.width = width;
        this.height = height;
        this.depthOrLayers = depthOrLayers;
        this.mipLevels = mipLevels;
    }

    @Override
    public int getWidth(int mipLevel) {
        return Math.max(1, width >> mipLevel);
    }

    @Override
    public int getHeight(int mipLevel) {
        return Math.max(1, height >> mipLevel);
    }

    @Override
    public int getDepthOrLayers() {
        return depthOrLayers;
    }

    @Override
    public int getMipLevels() {
        return mipLevels;
    }

    @Override
    public GpuFormat getFormat() {
        return format;
    }

    @Override
    public @GpuTexture.Usage int usage() {
        return usage;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
