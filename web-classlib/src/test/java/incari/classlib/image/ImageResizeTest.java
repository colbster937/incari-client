package incari.classlib.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageResizeTest {

    @Test
    void halvingAveragesEachTwoByTwoBlock() {
        byte[] source = {
            0, 100, (byte) 200, (byte) 250, 50, (byte) 150, 10, 30,
        };

        byte[] result = ImageResize.resize(source, 4, 2, 1, false, 2, 1);

        assertThat(result).containsExactly((byte) 75, (byte) 123);
    }

    @Test
    void oddScalesShareABorderPixelByCoverage() {
        // Three pixels into two: each output covers one and a half source pixels.
        byte[] source = {0, 90, (byte) 180};

        byte[] result = ImageResize.resize(source, 3, 1, 1, false, 2, 1);

        assertThat(result).containsExactly((byte) 30, (byte) 150);
    }

    @Test
    void transparentPixelsDontBleedTheirColour() {
        // An opaque red pixel beside a fully transparent green one.
        byte[] source = {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, 0};

        byte[] result = ImageResize.resize(source, 2, 1, 4, true, 1, 1);

        assertThat(result).containsExactly((byte) 255, (byte) 0, (byte) 0, (byte) 128);
    }

    @Test
    void growingRepeatsPixels() {
        byte[] source = {10, 20};

        byte[] result = ImageResize.resize(source, 2, 1, 1, false, 4, 2);

        assertThat(result)
                .containsExactly(
                        (byte) 10, (byte) 10, (byte) 20, (byte) 20, (byte) 10, (byte) 10, (byte) 20, (byte) 20);
    }

    @Test
    void aWholeFrameShrinksToAnIcon() {
        byte[] frame = new byte[854 * 480 * 4];
        java.util.Arrays.fill(frame, (byte) 200);

        byte[] icon = ImageResize.resize(frame, 854, 480, 4, true, 64, 64);

        assertThat(icon).hasSize(64 * 64 * 4).containsOnly((byte) 200);
    }
}
