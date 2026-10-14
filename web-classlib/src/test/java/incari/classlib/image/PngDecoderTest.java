package incari.classlib.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

/** Decodes images the JDK's PNG writer produced and compares every pixel with the JDK's reader. */
class PngDecoderTest {

    private static final int[] TYPES = {
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_3BYTE_BGR,
        BufferedImage.TYPE_BYTE_GRAY,
        BufferedImage.TYPE_USHORT_GRAY,
        BufferedImage.TYPE_BYTE_INDEXED,
        BufferedImage.TYPE_BYTE_BINARY,
    };

    @Test
    void decodesLikeTheJdk() throws IOException {
        Random random = new Random(7);
        for (int type : TYPES) {
            for (boolean interlaced : new boolean[] {false, true}) {
                BufferedImage source = new BufferedImage(13, 9, type);
                for (int y = 0; y < source.getHeight(); y++) {
                    for (int x = 0; x < source.getWidth(); x++) {
                        source.setRGB(x, y, random.nextInt());
                    }
                }
                byte[] file = write(source, interlaced);
                BufferedImage expected = ImageIO.read(new ByteArrayInputStream(file));
                PngDecoder.Image decoded = PngDecoder.decode(file);
                assertThat(decoded.width()).isEqualTo(13);
                assertThat(decoded.height()).isEqualTo(9);
                for (int y = 0; y < 9; y++) {
                    for (int x = 0; x < 13; x++) {
                        int at = (y * 13 + x) * 4;
                        byte[] rgba = decoded.rgba();
                        int argb = (rgba[at + 3] & 0xFF) << 24
                                | (rgba[at] & 0xFF) << 16
                                | (rgba[at + 1] & 0xFF) << 8
                                | (rgba[at + 2] & 0xFF);
                        assertThat(argb)
                                .as("type %d, interlaced %s, pixel %d,%d", type, interlaced, x, y)
                                .isEqualTo(expectedArgb(expected, x, y));
                    }
                }
            }
        }
    }

    @Test
    void rejectsOtherFiles() {
        assertThatThrownBy(() -> PngDecoder.decode(new byte[] {1, 2, 3})).isInstanceOf(IOException.class);
        byte[] truncated = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};
        assertThatThrownBy(() -> PngDecoder.decode(truncated)).isInstanceOf(IOException.class);
    }

    /**
     * The JDK's reading of a pixel. Its gray images are linear and {@code getRGB} gamma-corrects
     * them, while PNG (and STB) keep the stored level, so gray compares by raw sample.
     */
    private static int expectedArgb(BufferedImage image, int x, int y) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_BYTE_GRAY || type == BufferedImage.TYPE_USHORT_GRAY) {
            int sample = image.getRaster().getSample(x, y, 0);
            int level = type == BufferedImage.TYPE_USHORT_GRAY ? sample >>> 8 : sample;
            return 0xFF000000 | level << 16 | level << 8 | level;
        }
        return image.getRGB(x, y);
    }

    private static byte[] write(BufferedImage image, boolean interlaced) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(interlaced ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
