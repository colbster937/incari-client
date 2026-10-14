package incari.classlib.substitutes.org.lwjgl.stb;

import incari.classlib.image.PngDecoder;
import incari.classlib.memory.Heap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;

/**
 * LWJGL's {@code STBImage}, as far as {@code NativeImage} uses it: PNG files (the only format
 * the game loads) decoded by {@link PngDecoder} into {@link Heap} memory. A stand-in until the
 * browser build decodes images natively.
 */
@SuppressWarnings("TypeName") // The name is LWJGL's class, as the class library maps it.
public final class TSTBImage {

    private static String failure = "no failure";

    private TSTBImage() {}

    /**
     * Decodes {@code buffer}'s remaining bytes, or answers null and sets the failure reason.
     *
     * @param channels the channels to return: 4 (RGBA, all the game asks for) or 0 (as in the file)
     */
    public static @Nullable ByteBuffer stbi_load_from_memory(
            ByteBuffer buffer, IntBuffer width, IntBuffer height, IntBuffer channelsInFile, int channels) {
        if (channels != 4 && channels != 0) {
            failure = "only RGBA output is supported";
            return null;
        }
        byte[] file = new byte[buffer.remaining()];
        buffer.duplicate().get(file);
        PngDecoder.Image image;
        try {
            image = PngDecoder.decode(file);
        } catch (IOException e) {
            failure = e.getMessage() != null ? e.getMessage() : "unreadable image";
            return null;
        }
        width.put(width.position(), image.width());
        height.put(height.position(), image.height());
        channelsInFile.put(channelsInFile.position(), image.channels());
        ByteBuffer pixels = Heap.allocateBuffer(image.rgba().length, false);
        pixels.put(image.rgba());
        pixels.rewind();
        return pixels;
    }

    public static String stbi_failure_reason() {
        return failure;
    }

    public static void nstbi_image_free(long address) {
        Heap.free(address);
    }
}
