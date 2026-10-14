package incari.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.PcmFormat;
import incari.platform.api.window.DisplayInfo;
import incari.platform.api.window.DisplayMode;
import incari.platform.api.window.IconImage;
import incari.platform.api.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @AfterEach
    void uninstall() {
        Platforms.uninstallForTests();
    }

    @Test
    void windowsCantBeSmallerThanTheGuiNeeds() {
        assertThat(new WindowSpec("t", 320, 240, 0).width()).isEqualTo(320);
        assertThatThrownBy(() -> new WindowSpec("t", 319, 240, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WindowSpec("t", 320, 239, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pcmFormatsAreLimitedToWhatEveryBackendPlays() {
        assertThat(new PcmFormat(44_100, 2, 16).frameSize()).isEqualTo(4);
        assertThat(new PcmFormat(22_050, 1, 8).frameSize()).isEqualTo(1);
        assertThatThrownBy(() -> new PcmFormat(44_100, 6, 16)).isInstanceOf(AudioException.class);
        assertThatThrownBy(() -> new PcmFormat(44_100, 2, 24)).isInstanceOf(AudioException.class);
        assertThatThrownBy(() -> new PcmFormat(0, 2, 16)).isInstanceOf(AudioException.class);
    }

    @Test
    void iconsNeedExactlyFourBytesPerPixel() {
        assertThat(new IconImage(2, 2, new byte[16]).width()).isEqualTo(2);
        assertThatThrownBy(() -> new IconImage(2, 2, new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displayModeListsAreCopied() {
        List<DisplayMode> modes = new ArrayList<>(List.of(new DisplayMode(1920, 1080, 8, 8, 8, 60.0F)));
        DisplayInfo display = new DisplayInfo(1, "d", 0, 0, 1920, 1080, modes.getFirst(), modes);
        modes.clear();
        assertThat(display.fullscreenModes()).hasSize(1);
    }

    @Test
    void onePlatformPerProcess() {
        assertThatThrownBy(Platforms::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app-desktop or app-web");
        assertThat(Platforms.isInstalled()).isFalse();
    }
}
