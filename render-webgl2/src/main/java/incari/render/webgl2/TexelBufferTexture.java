package incari.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.NEAREST;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_2D;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MAG_FILTER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.TEXTURE_MIN_FILTER;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNPACK_ROW_LENGTH;

import com.mojang.renderpearl.api.GpuFormat;
import incari.render.webgl2.shaders.TexelBuffers;
import org.teavm.jso.webgl.WebGL2RenderingContext;
import org.teavm.jso.webgl.WebGLTexture;

/**
 * A texel buffer emulated as a 2D texture (WebGL2 has no texel buffers): the buffer's elements
 * row by row, {@link TexelBuffers#WIDTH} to a row, which is how the build rewrote the shaders'
 * {@code texelFetch} calls. The copy is refreshed from the buffer's own memory when the buffer has
 * changed since, so a buffer the game rewrites now and then (the clouds' faces, rebuilt when the
 * camera crosses a cloud cell) costs one upload per change, not one per draw.
 */
final class TexelBufferTexture {

    private final WebGl2Device device;
    private final WebGLTexture handle;
    private final GpuFormat format;
    private final GlFormats.TextureFormat glFormat;
    private final int elements;
    private int version = -1;

    TexelBufferTexture(WebGl2Device device, GpuFormat format, long bufferSize) {
        GlFormats.TextureFormat texture = GlFormats.texture(format);
        if (texture == null) {
            throw new UnsupportedOperationException("WebGL2 has no " + format + " textures for a texel buffer");
        }
        this.device = device;
        this.format = format;
        this.glFormat = texture;
        this.elements = Math.toIntExact(bufferSize / format.blockSize());
        WebGL2RenderingContext gl = device.gl();
        handle = gl.createTexture();
        device.bindTextureForUpdate(TEXTURE_2D, handle);
        gl.texStorage2D(TEXTURE_2D, 1, texture.internalFormat(), TexelBuffers.WIDTH, TexelBuffers.rows(elements));
        // Integer textures can only be sampled with nearest filtering; texelFetch ignores it anyway.
        gl.texParameteri(TEXTURE_2D, TEXTURE_MIN_FILTER, NEAREST);
        gl.texParameteri(TEXTURE_2D, TEXTURE_MAG_FILTER, NEAREST);
    }

    GpuFormat format() {
        return format;
    }

    WebGLTexture handle() {
        return handle;
    }

    /** Copies {@code buffer} in if it has changed since the last copy. */
    void refresh(WebGl2Buffer buffer) {
        if (buffer.version() == version) {
            return;
        }
        version = buffer.version();
        WebGL2RenderingContext gl = device.gl();
        device.bindTextureForUpdate(TEXTURE_2D, handle);
        int blockSize = format.blockSize();
        int fullRows = elements / TexelBuffers.WIDTH;
        gl.pixelStorei(UNPACK_ROW_LENGTH, 0);
        if (fullRows > 0) {
            device.staging()
                    .texSubImage2D(
                            gl,
                            TEXTURE_2D,
                            0,
                            0,
                            0,
                            TexelBuffers.WIDTH,
                            fullRows,
                            glFormat.format(),
                            glFormat.type(),
                            buffer.memory(),
                            0,
                            fullRows * TexelBuffers.WIDTH * blockSize);
        }
        int rest = elements - fullRows * TexelBuffers.WIDTH;
        if (rest > 0) {
            device.staging()
                    .texSubImage2D(
                            gl,
                            TEXTURE_2D,
                            0,
                            0,
                            fullRows,
                            rest,
                            1,
                            glFormat.format(),
                            glFormat.type(),
                            buffer.memory(),
                            fullRows * TexelBuffers.WIDTH * blockSize,
                            rest * blockSize);
        }
    }

    void close() {
        device.gl().deleteTexture(handle);
    }
}
