package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ImageDiffTest {

    @Test
    void identicalImagesScoreOne() {
        BufferedImage image = checkerboard(64, 48, 0);
        ImageDiff.Result result = ImageDiff.compare(image, copy(image));
        assertThat(result.ssim()).isCloseTo(1.0, within(1e-6));
        assertThat(result.changed()).isZero();
    }

    @Test
    void faintNoiseCostsLittleAndMissingDetailCostsMuch() {
        BufferedImage image = checkerboard(64, 48, 0);
        BufferedImage noisy = copy(image);
        Random random = new Random(1);
        for (int y = 0; y < noisy.getHeight(); y++) {
            for (int x = 0; x < noisy.getWidth(); x++) {
                int rgb = noisy.getRGB(x, y);
                int delta = random.nextInt(7) - 3;
                int red = Math.clamp(((rgb >> 16) & 0xFF) + delta, 0, 255);
                noisy.setRGB(x, y, (rgb & 0xFF00FFFF) | (red << 16));
            }
        }
        BufferedImage flat = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageDiff.compare(image, noisy).ssim()).isGreaterThan(0.95);
        assertThat(ImageDiff.compare(image, flat).ssim()).isLessThan(0.2);
        assertThat(ImageDiff.compare(image, flat).changed()).isGreaterThan(0.4);
    }

    @Test
    void aWrongColorIsSeen() {
        BufferedImage image = checkerboard(64, 48, 0);
        BufferedImage tinted = checkerboard(64, 48, 0x00FF00);
        assertThat(ImageDiff.compare(image, tinted).ssim()).isLessThan(0.8);
    }

    @Test
    void sizesMustMatch() {
        assertThatThrownBy(() -> ImageDiff.compare(checkerboard(8, 8, 0), checkerboard(8, 9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BufferedImage checkerboard(int width, int height, int tint) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = ((x / 4 + y / 4) % 2 == 0) ? 0x303030 : 0xD0D0D0;
                image.setRGB(x, y, value | tint);
            }
        }
        return image;
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.setData(image.getData());
        return copy;
    }
}
