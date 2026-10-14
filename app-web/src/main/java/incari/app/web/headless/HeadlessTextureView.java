package incari.app.web.headless;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/** A view of a {@link HeadlessTexture}'s mip levels. */
final class HeadlessTextureView implements GpuTextureView {

    private final GpuTexture texture;
    private final int baseMipLevel;
    private final int mipLevels;
    private boolean closed;

    HeadlessTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        this.texture = texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevels = mipLevels;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public GpuTexture texture() {
        return texture;
    }

    @Override
    public int baseMipLevel() {
        return baseMipLevel;
    }

    @Override
    public int mipLevels() {
        return mipLevels;
    }

    @Override
    public int getWidth(int mipLevel) {
        return texture.getWidth(baseMipLevel + mipLevel);
    }

    @Override
    public int getHeight(int mipLevel) {
        return texture.getHeight(baseMipLevel + mipLevel);
    }

    @Override
    public void close() {
        closed = true;
    }
}
