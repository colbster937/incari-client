package incari.platform.web.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The bookkeeping behind pausing, pitch changes and buffer queues, which Web Audio doesn't keep. */
class PlaybackTest {

    @Test
    void clockFollowsPausesAndPitch() {
        PlaybackClock clock = new PlaybackClock();
        assertThat(clock.position(5)).isZero();
        clock.start(10);
        assertThat(clock.position(12)).isEqualTo(2);
        // Twice the pitch from 12 on: twice as fast through the samples.
        clock.setRate(12, 2);
        assertThat(clock.position(13)).isEqualTo(4);
        clock.pause(13);
        assertThat(clock.position(100)).isEqualTo(4);
        clock.start(100);
        assertThat(clock.position(100.5)).isEqualTo(5);
        // A context clock read slightly behind the anchor never moves it back.
        assertThat(clock.position(99)).isEqualTo(4);
        clock.reset();
        assertThat(clock.position(200)).isZero();
        assertThat(clock.running()).isFalse();
        clock.seek(200, 3);
        assertThat(clock.position(300)).isEqualTo(3);
    }

    @Test
    void loopsWrapWithinTheSound() {
        assertThat(PlaybackClock.wrap(2.5, 1)).isCloseTo(0.5, within(1e-12));
        assertThat(PlaybackClock.wrap(0.25, 1)).isEqualTo(0.25);
        assertThat(PlaybackClock.wrap(-0.25, 1)).isEqualTo(0.75);
        assertThat(PlaybackClock.wrap(3, 0)).isZero();
    }

    @Test
    void queueFinishesBuffersInOrder() {
        StreamTimeline timeline = new StreamTimeline();
        for (int i = 0; i < 4; i++) {
            timeline.add(1);
        }
        assertThat(timeline.end()).isEqualTo(4);
        assertThat(timeline.finishedAt(0.5)).isZero();
        assertThat(timeline.finishedAt(1)).isEqualTo(1);
        assertThat(timeline.finishedAt(2.9)).isEqualTo(2);
        assertThat(timeline.indexAt(2.9)).isEqualTo(2);
        assertThat(timeline.startOf(2)).isEqualTo(2);

        // Released buffers keep their places in the stream: the next one still starts at 3.
        timeline.release(3);
        assertThat(timeline.size()).isEqualTo(1);
        assertThat(timeline.startOf(0)).isEqualTo(3);
        timeline.add(0.5);
        assertThat(timeline.end()).isEqualTo(4.5);
        assertThat(timeline.finishedAt(4.2)).isEqualTo(1);
        assertThat(timeline.finishedAt(10)).isEqualTo(2);

        for (int i = 0; i < 20; i++) {
            timeline.add(0.25);
        }
        assertThat(timeline.end()).isEqualTo(9.5);
        timeline.clear();
        assertThat(timeline.size()).isZero();
        assertThat(timeline.end()).isZero();
    }

    @Test
    void readsTheOggVorbisHeader() {
        assertThat(OggVorbisInfo.read(oggHeader(1, 44100))).satisfies(info -> {
            assertThat(info.channels()).isEqualTo(1);
            assertThat(info.sampleRate()).isEqualTo(44100);
        });
        OggVorbisInfo stereo = OggVorbisInfo.read(oggHeader(2, 48000));
        assertThat(stereo).isNotNull();
        assertThat(stereo.channels()).isEqualTo(2);
        assertThat(OggVorbisInfo.read("not a sound".getBytes(StandardCharsets.US_ASCII)))
                .isNull();
        byte[] opus = oggHeader(2, 48000);
        opus[28] = 'O';
        assertThat(OggVorbisInfo.read(opus)).isNull();
    }

    /** The first page of an Ogg Vorbis file: its identification header, as the format lays it out. */
    private static byte[] oggHeader(int channels, int rate) {
        byte[] page = new byte[27 + 1 + 30];
        byte[] magic = "OggS".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, page, 0, 4);
        page[5] = 2; // Beginning of stream.
        page[26] = 1; // One segment...
        page[27] = 30; // ...of 30 bytes: the identification header.
        int packet = 28;
        page[packet] = 1;
        byte[] vorbis = "vorbis".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(vorbis, 0, page, packet + 1, 6);
        page[packet + 11] = (byte) channels;
        page[packet + 12] = (byte) rate;
        page[packet + 13] = (byte) (rate >> 8);
        page[packet + 14] = (byte) (rate >> 16);
        page[packet + 15] = (byte) (rate >> 24);
        return page;
    }
}
