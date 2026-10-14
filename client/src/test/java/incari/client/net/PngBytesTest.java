package incari.client.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PngBytesTest {

    @Test
    void writesAPngAnyDecoderReadsBack() throws Exception {
        byte[] rgba = new byte[64 * 64 * 4];
        for (int i = 0; i < 64 * 64; i++) {
            rgba[i * 4] = (byte) i;
            rgba[i * 4 + 1] = (byte) (i >> 4);
            rgba[i * 4 + 2] = (byte) 0x80;
            rgba[i * 4 + 3] = (byte) (i % 2 == 0 ? 0xFF : 0x40);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PngBytes.encode(64, 64, rgba)));

        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getHeight()).isEqualTo(64);
        for (int i = 0; i < 64 * 64; i++) {
            int argb = image.getRGB(i % 64, i / 64);
            assertThat(argb)
                    .isEqualTo(((rgba[i * 4 + 3] & 0xFF) << 24)
                            | ((rgba[i * 4] & 0xFF) << 16)
                            | ((rgba[i * 4 + 1] & 0xFF) << 8)
                            | (rgba[i * 4 + 2] & 0xFF));
        }
    }
}
