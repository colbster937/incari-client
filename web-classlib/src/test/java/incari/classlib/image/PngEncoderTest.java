package incari.classlib.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.classlib.memory.Heap;
import incari.classlib.substitutes.org.lwjgl.stb.TSTBIWriteCallback;
import incari.classlib.substitutes.org.lwjgl.stb.TSTBImageWrite;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** PNGs the encoder writes, read back by the JDK's reader and by {@link PngDecoder}. */
class PngEncoderTest {

    @Test
    void theJdkReadsBackEveryPixel() throws IOException {
        Random random = new Random(3);
        for (int channels = 1; channels <= 4; channels++) {
            int width = 37;
            int height = 23;
            byte[] pixels = new byte[width * height * channels];
            // Smooth areas (so every filter wins somewhere) and noise.
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) (i < pixels.length / 2 ? i / 7 : random.nextInt());
            }
            byte[] png = PngEncoder.encode(width, height, channels, pixels);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(image.getWidth()).isEqualTo(width);
            assertThat(image.getHeight()).isEqualTo(height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int[] expected = new int[channels];
                    for (int c = 0; c < channels; c++) {
                        expected[c] = pixels[(y * width + x) * channels + c] & 0xFF;
                    }
                    assertThat(image.getRaster().getPixel(x, y, (int[]) null))
                            .as("pixel %d,%d with %d channels", x, y, channels)
                            .containsExactly(expected);
                }
            }
            if (channels == 4) {
                assertThat(PngDecoder.decode(png).rgba()).isEqualTo(pixels);
            }
        }
    }

    @Test
    void refusesImpossibleImages() {
        assertThatThrownBy(() -> PngEncoder.encode(0, 1, 4, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PngEncoder.encode(2, 2, 4, new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stbsWriterHandsTheCallbackAPngOfTheImageInMemory() throws IOException {
        int width = 5;
        int height = 3;
        int stride = width * 4 + 4;
        ByteBuffer pixels = Heap.allocateBuffer(stride * height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width * 4; x++) {
                pixels.put(y * stride + x, (byte) (y * 40 + x));
            }
        }
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        TSTBIWriteCallback callback = new TSTBIWriteCallback() {
            @Override
            public void invoke(long context, long data, int size) {
                ByteBuffer bytes = getData(data, size);
                byte[] copy = new byte[size];
                bytes.get(copy);
                written.writeBytes(copy);
            }
        };
        try {
            assertThat(TSTBImageWrite.nstbi_write_png_to_func(
                            callback.address(), 0, width, height, 4, Heap.address(pixels), stride))
                    .isEqualTo(1);
        } finally {
            callback.free();
            Heap.free(pixels);
        }
        PngDecoder.Image image = PngDecoder.decode(written.toByteArray());
        assertThat(image.width()).isEqualTo(width);
        assertThat(image.rgba()[4 * width]).isEqualTo((byte) 40);
        // A freed callback is gone: STB's answer is failure.
        assertThat(TSTBImageWrite.nstbi_write_png_to_func(callback.address() + 1000, 0, 1, 1, 4, 0, 0))
                .isZero();
    }
}
