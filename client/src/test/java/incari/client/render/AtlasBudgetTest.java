package incari.client.render;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.tier.DeviceTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AtlasBudgetTest {

    @AfterEach
    void restoreTier() {
        DeviceTier.set(DeviceTier.HIGH);
    }

    @Test
    void vanillasLargestAtlasFitsEveryTier() {
        for (DeviceTier tier : DeviceTier.values()) {
            assertThat(AtlasBudget.maxSize(tier)).isGreaterThanOrEqualTo(2048);
        }
    }

    @Test
    void theDevicesLimitWinsWhenItsSmaller() {
        DeviceTier.set(DeviceTier.HIGH);
        assertThat(AtlasBudget.maxSize(8192)).isEqualTo(8192);
        DeviceTier.set(DeviceTier.POTATO);
        assertThat(AtlasBudget.maxSize(8192)).isEqualTo(2048);
    }

    @Test
    void averagesOpaquePixels() {
        int mean = AtlasBudget.mean(0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF);
        assertThat(mean).isEqualTo(0xFF808080);
    }

    @Test
    void transparentPixelsLendNoColour() {
        // One opaque red pixel among three transparent white ones: red, at a quarter of the alpha.
        int mean = AtlasBudget.mean(0xFFFF0000, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF);
        assertThat(mean).isEqualTo(0x3FFF0000);
        assertThat(AtlasBudget.mean(0, 0, 0, 0)).isZero();
    }
}
