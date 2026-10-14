package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_2D;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_BASE_LEVEL;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_CUBE_MAP;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_CUBE_MAP_POSITIVE_X;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MAX_LEVEL;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLTexture;

/**
 * A texture with immutable storage ({@code texStorage2D}): every mip level exists from the start,
 * so the texture is always complete, whatever gets uploaded when. A cubemap-compatible texture is
 * a WebGL cube map with its six faces as the game's six layers.
 */
final class WebGl2Texture extends BaseGpuTexture {

    private final WebGl2Device device;
    private final WebGLTexture handle;
    private final int target;
    private final GlFormats.TextureFormat glFormat;
    private int baseLevel;
    private int maxLevel;
    private boolean closed;

    WebGl2Texture(
            WebGl2Device device,
            @GpuTexture.Usage int usage,
            String label,
            GpuFormat format,
            GlFormats.TextureFormat glFormat,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.glFormat = glFormat;
        this.target = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? TEXTURE_CUBE_MAP : TEXTURE_2D;
        WebGL2RenderingContext gl = device.gl();
        handle = gl.createTexture();
        device.bindTextureForUpdate(target, handle);
        gl.texStorage2D(target, mipLevels, glFormat.internalFormat(), width, height);
        baseLevel = 0;
        maxLevel = mipLevels - 1;
        gl.texParameteri(target, TEXTURE_BASE_LEVEL, baseLevel);
        gl.texParameteri(target, TEXTURE_MAX_LEVEL, maxLevel);
    }

    WebGLTexture handle() {
        return handle;
    }

    int target() {
        return target;
    }

    GlFormats.TextureFormat glFormat() {
        return glFormat;
    }

    /** The 2D image target of one layer: the texture itself, or one face of a cube map. */
    int imageTarget(int layer) {
        return target == TEXTURE_CUBE_MAP ? TEXTURE_CUBE_MAP_POSITIVE_X + layer : TEXTURE_2D;
    }

    /** Whether sampling through a view of these levels needs {@link #restrictLevels}. */
    boolean needsLevels(int base, int count) {
        return base != baseLevel || base + count - 1 != maxLevel;
    }

    /**
     * Limits sampling to a view's mip levels. WebGL2 has no texture views, so the range is texture
     * state, set when a view is bound for sampling; the texture must be bound on the active unit.
     */
    void restrictLevels(int base, int count) {
        int max = base + count - 1;
        if (base != baseLevel) {
            device.gl().texParameteri(target, TEXTURE_BASE_LEVEL, base);
            baseLevel = base;
        }
        if (max != maxLevel) {
            device.gl().texParameteri(target, TEXTURE_MAX_LEVEL, max);
            maxLevel = max;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.framebuffers().forget(this);
            device.gl().deleteTexture(handle);
        }
    }
}
