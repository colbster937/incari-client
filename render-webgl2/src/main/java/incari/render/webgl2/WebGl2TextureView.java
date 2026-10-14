package incari.render.webgl2;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

/**
 * A range of a texture's mip levels. WebGL2 has no view objects: sampling through a view limits the
 * texture's levels when it's bound, and rendering to one attaches its base level.
 */
final class WebGl2TextureView extends BaseGpuTextureView {

    private boolean closed;

    WebGl2TextureView(WebGl2Texture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
    }

    WebGl2Texture glTexture() {
        return (WebGl2Texture) texture();
    }

    @Override
    public boolean isClosed() {
        return closed || texture().isClosed();
    }

    @Override
    public void close() {
        closed = true;
    }
}
