package incari.classlib.substitutes.org.lwjgl.stb;

import incari.classlib.image.ImageResize;
import incari.classlib.memory.Heap;

/**
 * LWJGL's {@code STBImageResize}, as far as {@code NativeImage.resizeSubRectTo} uses it: the
 * linear 8-bit resize, done by {@link ImageResize} over {@link Heap} memory. The game shrinks a
 * frame to a singleplayer world's icon with it.
 */
@SuppressWarnings("TypeName") // The name is LWJGL's class, as the class library maps it.
public final class TSTBImageResize {

    // STB's pixel layouts for 1 to 4 channels; RGBA weights the colours by alpha.
    public static final int STBIR_1CHANNEL = 1;
    public static final int STBIR_2CHANNEL = 2;
    public static final int STBIR_RGB = 3;
    public static final int STBIR_RGBA = 4;
    public static final int STBIR_4CHANNEL = 5;

    private TSTBImageResize() {}

    /**
     * Resizes the image at {@code input} into {@code output}.
     *
     * @param inputStride bytes from one input row to the next, or 0 for {@code width * channels}
     * @param outputStride the same for the output
     * @param layout one of the {@code STBIR_} layouts
     * @return {@code output}, or 0 if the arguments make no image (as STB answers)
     */
    public static long nstbir_resize_uint8_linear(
            long input,
            int inputWidth,
            int inputHeight,
            int inputStride,
            long output,
            int outputWidth,
            int outputHeight,
            int outputStride,
            int layout) {
        int components = layout == STBIR_4CHANNEL ? 4 : layout;
        if (output == 0
                || components < 1
                || components > 4
                || inputWidth <= 0
                || inputHeight <= 0
                || outputWidth <= 0
                || outputHeight <= 0) {
            return 0;
        }
        int inputRow = inputWidth * components;
        int inputStep = inputStride == 0 ? inputRow : inputStride;
        byte[] source = new byte[inputRow * inputHeight];
        for (int y = 0; y < inputHeight; y++) {
            Heap.buffer(input + (long) y * inputStep, inputRow).get(source, y * inputRow, inputRow);
        }
        byte[] resized = ImageResize.resize(
                source, inputWidth, inputHeight, components, layout == STBIR_RGBA, outputWidth, outputHeight);
        int outputRow = outputWidth * components;
        int outputStep = outputStride == 0 ? outputRow : outputStride;
        for (int y = 0; y < outputHeight; y++) {
            Heap.buffer(output + (long) y * outputStep, outputRow).put(resized, y * outputRow, outputRow);
        }
        return output;
    }
}
