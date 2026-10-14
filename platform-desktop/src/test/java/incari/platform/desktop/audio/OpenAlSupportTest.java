package incari.platform.desktop.audio;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.audio.PcmFormat;
import org.junit.jupiter.api.Test;

/** Runs without LWJGL: OpenAlSupport only uses its inlined constants. */
class OpenAlSupportTest {

    // OpenAL 1.1 specification values, written out so the test doesn't just repeat the code.
    private static final int FORMAT_MONO8 = 0x1100;
    private static final int FORMAT_MONO16 = 0x1101;
    private static final int FORMAT_STEREO8 = 0x1102;
    private static final int FORMAT_STEREO16 = 0x1103;
    private static final int MONO_SOURCES = 0x1010;
    private static final int FREQUENCY = 0x1007;
    private static final int HRTF = 0x1992;
    private static final int HRTF_ID = 0x1996;
    private static final int OUTPUT_LIMITER = 0x199A;

    @Test
    void mapsEveryPcmLayoutToItsOpenAlFormat() {
        assertThat(OpenAlSupport.alFormat(new PcmFormat(44100, 1, 8))).isEqualTo(FORMAT_MONO8);
        assertThat(OpenAlSupport.alFormat(new PcmFormat(44100, 1, 16))).isEqualTo(FORMAT_MONO16);
        assertThat(OpenAlSupport.alFormat(new PcmFormat(22050, 2, 8))).isEqualTo(FORMAT_STEREO8);
        assertThat(OpenAlSupport.alFormat(new PcmFormat(48000, 2, 16))).isEqualTo(FORMAT_STEREO16);
    }

    @Test
    void asksForHrtfOnlyWhenTheDeviceHasSome() {
        assertThat(OpenAlSupport.contextAttributes(0, true, false)).containsExactly(0);
        assertThat(OpenAlSupport.contextAttributes(2, true, false)).containsExactly(HRTF, 1, HRTF_ID, 0, 0);
        assertThat(OpenAlSupport.contextAttributes(2, false, false)).containsExactly(HRTF, 0, HRTF_ID, 0, 0);
    }

    @Test
    void turnsTheLimiterOnWhenSupported() {
        assertThat(OpenAlSupport.contextAttributes(0, false, true)).containsExactly(OUTPUT_LIMITER, 1, 0);
        assertThat(OpenAlSupport.contextAttributes(1, true, true))
                .containsExactly(HRTF, 1, HRTF_ID, 0, OUTPUT_LIMITER, 1, 0);
    }

    @Test
    void readsTheMonoSourceCountFromTheAttributes() {
        assertThat(OpenAlSupport.monoSourceCount(new int[] {FREQUENCY, 48000, MONO_SOURCES, 255, 0}))
                .isEqualTo(255);
    }

    @Test
    void fallsBackWhenTheAttributesDontSay() {
        assertThat(OpenAlSupport.monoSourceCount(new int[0])).isEqualTo(OpenAlSupport.FALLBACK_SOURCE_COUNT);
        assertThat(OpenAlSupport.monoSourceCount(new int[] {FREQUENCY, 48000, 0, 0}))
                .isEqualTo(OpenAlSupport.FALLBACK_SOURCE_COUNT);
        // Nothing after the terminator counts.
        assertThat(OpenAlSupport.monoSourceCount(new int[] {0, 0, MONO_SOURCES, 64}))
                .isEqualTo(OpenAlSupport.FALLBACK_SOURCE_COUNT);
        // A truncated list (odd length) doesn't read past its end.
        assertThat(OpenAlSupport.monoSourceCount(new int[] {FREQUENCY, 48000, MONO_SOURCES}))
                .isEqualTo(OpenAlSupport.FALLBACK_SOURCE_COUNT);
    }
}
