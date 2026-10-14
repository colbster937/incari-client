package incari.classlib.substitutes.javax.sound.sampled;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.Test;

/** The stand-in against the JDK's own {@code AudioFormat}. */
class AudioFormatTest {

    @Test
    void linearPcmMatchesTheJdk() {
        for (int channels : new int[] {1, 2}) {
            for (int bits : new int[] {8, 16, 24}) {
                for (boolean signed : new boolean[] {true, false}) {
                    AudioFormat jdk = new AudioFormat(44100, bits, channels, signed, false);
                    TAudioFormat ours = new TAudioFormat(44100, bits, channels, signed, false);
                    assertThat(ours.getEncoding().toString())
                            .isEqualTo(jdk.getEncoding().toString());
                    assertThat(ours.getSampleRate()).isEqualTo(jdk.getSampleRate());
                    assertThat(ours.getSampleSizeInBits()).isEqualTo(jdk.getSampleSizeInBits());
                    assertThat(ours.getChannels()).isEqualTo(jdk.getChannels());
                    assertThat(ours.getFrameSize()).isEqualTo(jdk.getFrameSize());
                    assertThat(ours.getFrameRate()).isEqualTo(jdk.getFrameRate());
                    assertThat(ours.isBigEndian()).isEqualTo(jdk.isBigEndian());
                }
            }
        }
    }

    @Test
    void unknownSizesLeaveTheFrameSizeUnknown() {
        assertThat(new TAudioFormat(48000, -1, 2, true, false).getFrameSize())
                .isEqualTo(new AudioFormat(48000, -1, 2, true, false).getFrameSize());
    }

    @Test
    void encodingsCompareByName() {
        assertThat(new TAudioFormat.Encoding("PCM_SIGNED")).isEqualTo(TAudioFormat.Encoding.PCM_SIGNED);
        assertThat(new TAudioFormat.Encoding("PCM_SIGNED").hashCode())
                .isEqualTo(TAudioFormat.Encoding.PCM_SIGNED.hashCode());
        assertThat(TAudioFormat.Encoding.PCM_FLOAT).isNotEqualTo(TAudioFormat.Encoding.PCM_SIGNED);
        assertThat(TAudioFormat.Encoding.PCM_UNSIGNED.toString())
                .isEqualTo(AudioFormat.Encoding.PCM_UNSIGNED.toString());
    }

    @Test
    void fullConstructorKeepsEveryField() {
        TAudioFormat format = new TAudioFormat(TAudioFormat.Encoding.PCM_FLOAT, 48000, 32, 2, 8, 48000, true);
        assertThat(format.getEncoding()).isEqualTo(TAudioFormat.Encoding.PCM_FLOAT);
        assertThat(format.getFrameSize()).isEqualTo(8);
        assertThat(format.isBigEndian()).isTrue();
        assertThat(format.toString()).contains("PCM_FLOAT").contains("2 channels");
    }
}
