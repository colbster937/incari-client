package incari.platform.web.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The browser's panning and attenuation against the desktop's, measured. Each row is a 441 Hz
 * tone played through LWJGL 3.4.3's OpenAL Soft (the game's) with its wave-file backend writing
 * stereo at 48 kHz: HRTF off and the output limiter on, as the desktop opens its device; the
 * listener at the origin facing -z with +y up; the RMS of each channel over the middle of the
 * tone, divided by the tone's own. {@code linear} is the game's linear model (maximum distance
 * 16); {@code none} is no attenuation. ADR 0013 describes the measurement.
 */
class SpatializerTest {

    /** OpenAL Soft's measured output is within this of the model everywhere. */
    private static final double TOLERANCE = 0.003;

    @ParameterizedTest(name = "({0}, {1}, {2}) {3} relative={4} stereo={5}")
    @CsvSource({
        // x, y, z, attenuation, relative, stereo, measured left, measured right
        "0, 0, -4, linear, false, false, 0.4466, 0.4466",
        "4, 0, 0, linear, false, false, 0.0000, 0.7497",
        "-4, 0, 0, linear, false, false, 0.7497, 0.0000",
        "2.828, 0, -2.828, linear, false, false, 0.0560, 0.7486",
        "2, 0, -3.464, linear, false, false, 0.1605, 0.6906",
        "3.464, 0, -2, linear, false, false, 0.0000, 0.7497",
        "2.828, 0, 2.828, linear, false, false, 0.0591, 0.5892",
        "0, 0, 4, linear, false, false, 0.3031, 0.3031",
        "0, 4, 0, linear, false, false, 0.3748, 0.3748",
        "2.828, 2.828, 0, linear, false, false, 0.1098, 0.6399",
        "8, 0, 0, linear, false, false, 0.0000, 0.4998",
        "12, 0, 0, linear, false, false, 0.0000, 0.2499",
        "16, 0, 0, linear, false, false, 0.0000, 0.0000",
        "20, 0, 0, linear, false, false, 0.0000, 0.0000",
        "1, 0, 0, linear, false, false, 0.0000, 0.9371",
        "0.1, 0, 0, linear, false, false, 0.0000, 0.9933",
        "0, 0, 0, linear, false, false, 0.5954, 0.5954",
        "0.5, 0, -4, linear, false, false, 0.3748, 0.5135",
        "1, 0, -4, linear, false, false, 0.3040, 0.5705",
        "4, 0, 0, none, false, false, 0.0000, 0.9996",
        "0, 0, 0, none, true, false, 0.5954, 0.5954",
        "4, 0, 0, linear, false, true, 0.9996, 0.9996",
        "0, 0, 0, none, true, true, 0.9996, 0.9996",
    })
    void matchesOpenAlSoft(
            double x,
            double y,
            double z,
            String attenuation,
            boolean relative,
            boolean stereo,
            double left,
            double right) {
        Spatializer spatializer = new Spatializer();
        spatializer.setListenerPosition(0, 0, 0);
        spatializer.setListenerOrientation(0, 0, -1, 0, 1, 0);
        Spatializer.Attenuation model = new Spatializer.Attenuation();
        if (attenuation.equals("linear")) {
            model.linear(16);
        } else {
            model.none();
        }
        spatializer.compute(x, y, z, relative, model, 1, stereo);
        assertThat(spatializer.left()).isCloseTo(left, within(TOLERANCE));
        assertThat(spatializer.right()).isCloseTo(right, within(TOLERANCE));
    }

    @Test
    void followsTheListenerAsTheGameMovesIt() {
        // The game's camera at yaw 0 faces +z (south); its right is then -x.
        Spatializer spatializer = new Spatializer();
        spatializer.setListenerPosition(10, 65.62, 10);
        spatializer.setListenerOrientation(0, 0, 1, 0, 1, 0);
        Spatializer.Attenuation model = new Spatializer.Attenuation();
        model.linear(16);
        spatializer.compute(6, 65.62, 10, false, model, 1, false);
        assertThat(spatializer.localX()).isCloseTo(4, within(1e-9));
        assertThat(spatializer.left()).isCloseTo(0, within(1e-9));
        assertThat(spatializer.right()).isCloseTo(0.75, within(1e-9));

        // Relative sources ignore where the listener is.
        spatializer.compute(0, 0, 0, true, model, 0.5, false);
        assertThat(spatializer.left()).isCloseTo(0.5 * (Spatializer.OMNI + Spatializer.FRONT), within(1e-9));
    }

    @Test
    void orientationNeedNotBeNormalizedOrPerpendicular() {
        Spatializer spatializer = new Spatializer();
        spatializer.setListenerOrientation(0, -3, -3, 0, 1, 0);
        Spatializer.Attenuation model = new Spatializer.Attenuation();
        model.none();
        // Straight ahead along the tilted view.
        spatializer.compute(0, -1, -1, false, model, 1, false);
        assertThat(spatializer.localX()).isCloseTo(0, within(1e-9));
        assertThat(spatializer.localY()).isCloseTo(0, within(1e-9));
        assertThat(spatializer.localZ()).isCloseTo(-Math.sqrt(2), within(1e-9));

        // A degenerate orientation is ignored.
        spatializer.setListenerOrientation(0, 1, 0, 0, 1, 0);
        spatializer.compute(0, -1, -1, false, model, 1, false);
        assertThat(spatializer.localZ()).isCloseTo(-Math.sqrt(2), within(1e-9));
    }

    @Test
    void attenuationModels() {
        Spatializer.Attenuation model = new Spatializer.Attenuation();
        assertThat(model.gain(0.5)).isEqualTo(1);
        assertThat(model.gain(4)).isEqualTo(0.25);
        model.linear(10);
        assertThat(model.gain(0)).isEqualTo(1);
        assertThat(model.gain(5)).isEqualTo(0.5);
        assertThat(model.gain(15)).isEqualTo(0);
        model.linear(0);
        assertThat(model.gain(15)).isEqualTo(1);
        model.none();
        assertThat(model.gain(1000)).isEqualTo(1);
    }

    /** The ratios app-web/browser-checks/audio-positional.steps expects the page to measure. */
    @Test
    void browserCheckExpectations() {
        Spatializer spatializer = new Spatializer();
        spatializer.setListenerPosition(0.5, 201.625, 0.5);
        spatializer.setListenerOrientation(0, 0, 1, 0, 1, 0);
        Spatializer.Attenuation model = new Spatializer.Attenuation();
        model.linear(16);

        spatializer.compute(-3.5, 201.625, 0.5, false, model, 1, false);
        assertThat(spatializer.left()).isCloseTo(0, within(1e-9));
        assertThat(spatializer.right()).isCloseTo(0.75, within(1e-9));

        spatializer.compute(2.5, 201.625, 4, false, model, 1, false);
        assertThat(spatializer.right() / spatializer.left()).isCloseTo(0.2360, within(1e-4));

        spatializer.compute(0.5, 201.625, 4.5, false, model, 1, false);
        double ahead = spatializer.left();
        assertThat(spatializer.right()).isCloseTo(ahead, within(1e-12));
        spatializer.compute(0.5, 201.625, -3.5, false, model, 1, false);
        assertThat(spatializer.left() / ahead).isCloseTo(0.6788, within(1e-4));
        spatializer.compute(0.5, 201.625, 12.5, false, model, 1, false);
        assertThat(spatializer.left() / ahead).isCloseTo(1.0 / 3, within(1e-9));
    }
}
