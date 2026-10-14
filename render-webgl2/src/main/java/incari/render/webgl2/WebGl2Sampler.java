package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.LINEAR_MIPMAP_LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST_MIPMAP_LINEAR;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MAG_FILTER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MAX_LOD;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MIN_FILTER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_WRAP_S;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_WRAP_T;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLSampler;

/**
 * A WebGL2 sampler object. Minification always blends between mip levels, as on the game's other
 * backends: a texture without mips has one level, and a view limits the levels, so the blend only
 * does anything where there are levels to blend.
 */
final class WebGl2Sampler implements GpuSampler {

    private final WebGl2Device device;
    private final WebGLSampler handle;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    WebGl2Sampler(
            WebGl2Device device,
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod) {
        this.device = device;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        WebGL2RenderingContext gl = device.gl();
        handle = gl.createSampler();
        gl.samplerParameteri(handle, TEXTURE_WRAP_S, GlFormats.wrap(addressModeU));
        gl.samplerParameteri(handle, TEXTURE_WRAP_T, GlFormats.wrap(addressModeV));
        gl.samplerParameteri(
                handle,
                TEXTURE_MIN_FILTER,
                minFilter == FilterMode.LINEAR ? LINEAR_MIPMAP_LINEAR : NEAREST_MIPMAP_LINEAR);
        gl.samplerParameteri(handle, TEXTURE_MAG_FILTER, magFilter == FilterMode.LINEAR ? LINEAR : NEAREST);
        if (maxLod.isPresent()) {
            gl.samplerParameterf(handle, TEXTURE_MAX_LOD, (float) maxLod.getAsDouble());
        }
        if (maxAnisotropy > 1 && device.hasAnisotropy()) {
            gl.samplerParameterf(handle, WebGl.TEXTURE_MAX_ANISOTROPY, maxAnisotropy);
        }
    }

    WebGLSampler handle() {
        return handle;
    }

    @Override
    public AddressMode getAddressModeU() {
        return addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return maxLod;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.gl().deleteSampler(handle);
        }
    }
}
