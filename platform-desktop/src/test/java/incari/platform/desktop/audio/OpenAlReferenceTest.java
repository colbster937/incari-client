package incari.platform.desktop.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import incari.platform.api.PlatformLog;
import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioSource;
import incari.platform.api.audio.PcmFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the desktop's sound actually sounds like, for the browser to match (ADR 0013): a tone
 * played through this platform's OpenAL device at known positions, recorded by OpenAL Soft's
 * wave-file backend, and the level of each channel measured.
 *
 * <p>Only {@code ./gradlew :platform-desktop:openAlReference} runs it: it points OpenAL Soft at
 * a configuration that writes to a file instead of a sound card ({@code ALSOFT_CONF}), which has
 * to be set before the JVM starts. The expected gains are the same measurements
 * {@code platform-web}'s {@code SpatializerTest} holds its model to, so a new OpenAL Soft that
 * pans differently fails here first.
 */
@EnabledIfEnvironmentVariable(named = "INCARI_OPENAL_WAVE", matches = ".+")
class OpenAlReferenceTest {

    private static final int RATE = 44100;
    private static final double AMPLITUDE = 8000.0 / 32768;
    private static final double TOLERANCE = 0.003;

    @ParameterizedTest(name = "({0}, {1}, {2}) {3} relative={4} stereo={5}")
    @CsvSource({
        // x, y, z, attenuation, relative, stereo, left, right: listener at the origin facing -z.
        "0, 0, -4, linear, false, false, 0.4466, 0.4466",
        "4, 0, 0, linear, false, false, 0.0000, 0.7497",
        "-4, 0, 0, linear, false, false, 0.7497, 0.0000",
        "2.828, 0, -2.828, linear, false, false, 0.0560, 0.7486",
        "2, 0, -3.464, linear, false, false, 0.1605, 0.6906",
        "2.828, 0, 2.828, linear, false, false, 0.0591, 0.5892",
        "0, 0, 4, linear, false, false, 0.3031, 0.3031",
        "0, 4, 0, linear, false, false, 0.3748, 0.3748",
        "8, 0, 0, linear, false, false, 0.0000, 0.4998",
        "20, 0, 0, linear, false, false, 0.0000, 0.0000",
        "0.5, 0, -4, linear, false, false, 0.3748, 0.5135",
        "4, 0, 0, none, false, false, 0.0000, 0.9996",
        "0, 0, 0, none, true, false, 0.5954, 0.5954",
        "4, 0, 0, linear, false, true, 0.9996, 0.9996",
    })
    void panningAndAttenuation(
            double x,
            double y,
            double z,
            String attenuation,
            boolean relative,
            boolean stereo,
            double left,
            double right)
            throws Exception {
        Path wave = Path.of(System.getenv("INCARI_OPENAL_WAVE"));
        OpenAlAudio audio = new OpenAlAudio(PlatformLog.NONE);
        try (AudioDevice device = audio.open(null, false)) {
            device.listener().setPosition(0, 0, 0);
            device.listener().setOrientation(0, 0, -1, 0, 1, 0);
            AudioSource source = device.createSource();
            assertThat(source).isNotNull();
            AudioBuffer buffer = device.createBuffer(tone(stereo ? 2 : 1), new PcmFormat(RATE, stereo ? 2 : 1, 16));
            source.setBuffer(buffer);
            source.setPosition(x, y, z);
            source.setRelative(relative);
            if (attenuation.equals("linear")) {
                source.setLinearAttenuation(16);
            } else {
                source.disableAttenuation();
            }
            source.play();
            Thread.sleep(700);
            source.close();
            buffer.close();
        } finally {
            audio.close();
        }
        double[] levels = levels(Files.readAllBytes(wave));
        assertThat(levels[0]).as("left").isCloseTo(left, within(TOLERANCE));
        assertThat(levels[1]).as("right").isCloseTo(right, within(TOLERANCE));
    }

    /** Half a second of a 441 Hz sine, 16-bit, the same in every channel. */
    private static ByteBuffer tone(int channels) {
        int frames = RATE / 2;
        ByteBuffer samples = ByteBuffer.allocateDirect(frames * 2 * channels).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            short value = (short) Math.round(AMPLITUDE * 32768 * Math.sin(2 * Math.PI * 441 * i / RATE));
            for (int c = 0; c < channels; c++) {
                samples.putShort(value);
            }
        }
        return samples.flip();
    }

    /**
     * Each channel's RMS over the middle half of the non-silent part of a stereo 32-bit float WAV,
     * relative to the tone's own RMS.
     */
    private static double[] levels(byte[] wav) throws IOException {
        ByteBuffer file = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int data = indexOf(wav, "data") + 8;
        int channels = file.getShort(indexOf(wav, "fmt ") + 10);
        int bits = file.getShort(indexOf(wav, "fmt ") + 22);
        if (channels != 2 || bits != 32) {
            throw new IOException(
                    "Expected stereo 32-bit float output, got " + channels + " channels, " + bits + " bits");
        }
        int frames = (wav.length - data) / 8;
        int first = -1;
        int last = -1;
        for (int i = 0; i < frames; i++) {
            float l = file.getFloat(data + i * 8);
            float r = file.getFloat(data + i * 8 + 4);
            if (Math.abs(l) > 1e-4 || Math.abs(r) > 1e-4) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return new double[] {0, 0};
        }
        int from = first + (last - first) / 4;
        int to = first + 3 * (last - first) / 4;
        double[] sums = new double[2];
        for (int i = from; i < to; i++) {
            for (int c = 0; c < 2; c++) {
                double sample = file.getFloat(data + i * 8 + c * 4);
                sums[c] += sample * sample;
            }
        }
        double reference = AMPLITUDE / Math.sqrt(2);
        return new double[] {Math.sqrt(sums[0] / (to - from)) / reference, Math.sqrt(sums[1] / (to - from)) / reference
        };
    }

    private static int indexOf(byte[] file, String chunk) throws IOException {
        byte[] name = chunk.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 12; i + name.length <= file.length; i++) {
            for (int j = 0; j < name.length; j++) {
                if (file[i + j] != name[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IOException("No " + chunk + " chunk");
    }
}
