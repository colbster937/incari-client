package incari.classlib.substitutes.org.lwjgl.stb;

import incari.classlib.image.PngEncoder;
import incari.classlib.memory.Heap;
import java.nio.ByteBuffer;

/**
 * LWJGL's {@code STBImageWrite}, as far as {@code NativeImage} uses it: PNG files (the game's
 * screenshots) encoded by {@link PngEncoder} from {@link Heap} memory and handed to a write
 * callback in one piece (STB hands them over in pieces; the callback appends either way).
 */
@SuppressWarnings("TypeName") // The name is LWJGL's class, as the class library maps it.
public final class TSTBImageWrite {

    private TSTBImageWrite() {}

    /**
     * Encodes the image at {@code data} and calls the callback {@code func} ({@link
     * TSTBIWriteCallback#address}) with the file.
     *
     * @param stride bytes from one row to the next, or 0 for {@code width * components}
     * @return 1 if the file was written, 0 if not (as STB answers)
     */
    public static int nstbi_write_png_to_func(
            long func, long context, int width, int height, int components, long data, int stride) {
        TSTBIWriteCallback callback = TSTBIWriteCallback.find(func);
        if (callback == null || width <= 0 || height <= 0 || components < 1 || components > 4) {
            return 0;
        }
        int row = width * components;
        int step = stride == 0 ? row : stride;
        byte[] pixels = new byte[row * height];
        for (int y = 0; y < height; y++) {
            Heap.buffer(data + (long) y * step, row).get(pixels, y * row, row);
        }
        byte[] png = PngEncoder.encode(width, height, components, pixels);
        ByteBuffer file = Heap.allocateBuffer(png.length, false);
        try {
            file.put(0, png);
            callback.invoke(context, Heap.address(file), png.length);
        } finally {
            Heap.free(file);
        }
        return 1;
    }
}
