package incari.render.webgl2.shaders;

/**
 * How texel buffers are emulated (WebGL2 has none): a texel buffer uniform becomes a 2D texture
 * {@link #WIDTH} texels wide, element {@code i} at ({@code i % WIDTH}, {@code i / WIDTH}). The
 * build rewrites each {@code texelFetch} to that coordinate, and the device copies the buffer into
 * such a texture; both read the width from here. 2048 is the smallest {@code MAX_TEXTURE_SIZE}
 * WebGL2 allows, so the layout works on every device, and a power of two keeps the shader's
 * arithmetic to a mask and a shift.
 */
public final class TexelBuffers {

    public static final int WIDTH_SHIFT = 11;
    public static final int WIDTH = 1 << WIDTH_SHIFT;

    private TexelBuffers() {}

    /** The rows a buffer of {@code elements} texels takes. */
    public static int rows(int elements) {
        return Math.max(1, (elements + WIDTH - 1) >> WIDTH_SHIFT);
    }
}
