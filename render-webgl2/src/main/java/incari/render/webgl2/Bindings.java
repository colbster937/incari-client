package incari.render.webgl2;

import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * What's bound where across draws: uniform buffer ranges by binding point, textures and samplers by
 * unit, vertex attribute divisors and which attribute arrays are on. Binding points and units are
 * the pipelines' uniform indices, so consecutive draws of the GUI mostly rebind nothing. Hot path:
 * no allocation.
 *
 * <p>Texture uploads bind on a unit of their own ({@link WebGl2Device#bindTextureForUpdate}), so
 * they never disturb what's cached here.
 */
final class Bindings {

    private final WebGL2RenderingContext gl;
    private final @Nullable Object[] uniformBuffers;
    private final long[] uniformOffsets;
    private final long[] uniformLengths;
    private final @Nullable Object[] textures;
    private final @Nullable Object[] samplers;
    private final int[] divisors;
    private int enabledAttributes;

    Bindings(WebGL2RenderingContext gl, int slots, int attributes) {
        this.gl = gl;
        uniformBuffers = new Object[slots];
        uniformOffsets = new long[slots];
        uniformLengths = new long[slots];
        textures = new Object[slots];
        samplers = new Object[slots];
        divisors = new int[attributes];
    }

    /** Records a uniform buffer range at {@code point}; true if it differs from what's bound. */
    boolean uniformBuffer(int point, WebGl2Buffer buffer, long offset, long length) {
        if (uniformBuffers[point] == buffer && uniformOffsets[point] == offset && uniformLengths[point] == length) {
            return false;
        }
        uniformBuffers[point] = buffer;
        uniformOffsets[point] = offset;
        uniformLengths[point] = length;
        return true;
    }

    /** Records a texture and sampler on {@code unit}; true if either differs from what's bound. */
    boolean texture(int unit, WebGl2Texture texture, WebGl2Sampler sampler) {
        if (textures[unit] == texture && samplers[unit] == sampler) {
            return false;
        }
        textures[unit] = texture;
        samplers[unit] = sampler;
        return true;
    }

    /**
     * Records a texel buffer's texture on {@code unit}, with no sampler object (the texture's own
     * nearest filtering applies); true if that isn't what's bound.
     */
    boolean texelBuffer(int unit, TexelBufferTexture texture) {
        if (textures[unit] == texture && samplers[unit] == null) {
            return false;
        }
        textures[unit] = texture;
        samplers[unit] = null;
        return true;
    }

    void divisor(int location, int divisor) {
        if (divisors[location] != divisor) {
            gl.vertexAttribDivisor(location, divisor);
            divisors[location] = divisor;
        }
    }

    /** Turns on exactly the attribute arrays in {@code mask} (bit {@code i} is location {@code i}). */
    void enableAttributes(int mask) {
        int changed = mask ^ enabledAttributes;
        for (int location = 0; changed != 0; location++, changed >>>= 1) {
            if ((changed & 1) != 0) {
                if ((mask & (1 << location)) != 0) {
                    gl.enableVertexAttribArray(location);
                } else {
                    gl.disableVertexAttribArray(location);
                }
            }
        }
        enabledAttributes = mask;
    }

    /** Forgets the texture on {@code unit}; something else was bound there. */
    void forgetTexture(int unit) {
        if (unit < textures.length) {
            textures[unit] = null;
            samplers[unit] = null;
        }
    }
}
