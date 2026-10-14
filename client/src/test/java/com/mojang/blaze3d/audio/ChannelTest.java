package com.mojang.blaze3d.audio;

import static org.assertj.core.api.Assertions.assertThat;

import incari.client.audio.CompressedSoundStream;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

class ChannelTest {

    private static final AudioFormat STEREO16 = new AudioFormat(44100, 16, 2, true, false);

    /** A stream of {@code chunks} full reads, then the empty buffer that marks its end. */
    private static final class Stream implements AudioStream {
        int chunksLeft;
        int lastRequest;
        boolean closed;

        Stream(int chunks) {
            this.chunksLeft = chunks;
        }

        @Override
        public AudioFormat getFormat() {
            return STEREO16;
        }

        @Override
        public ByteBuffer read(int expectedSize) {
            lastRequest = expectedSize;
            if (chunksLeft == 0) {
                return ByteBuffer.allocateDirect(0);
            }
            chunksLeft--;
            return ByteBuffer.allocateDirect(expectedSize);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void streamsOneSecondChunks() {
        assertThat(Channel.bytesPerSeconds(STEREO16, 1)).isEqualTo(44100 * 2 * 2);
        assertThat(Channel.bytesPerSeconds(new AudioFormat(22050, 8, 1, false, false), 2))
                .isEqualTo(44100);
    }

    @Test
    void keepsTheStreamQueueToppedUp() {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        Channel channel = Channel.create(device);
        assertThat(channel).isNotNull();
        FakeAudio.Source source = device.sources.getFirst();
        Stream stream = new Stream(10);

        channel.attachBufferStream(stream);
        assertThat(source.queue).hasSize(4);
        assertThat(stream.lastRequest).isEqualTo(44100 * 4);

        source.processed = 3;
        channel.updateStream();
        assertThat(source.queue).hasSize(4);
        assertThat(stream.chunksLeft).isEqualTo(3);

        // Past the end nothing more is queued, and the queue drains.
        source.processed = 4;
        channel.updateStream();
        assertThat(source.queue).hasSize(3);
        assertThat(stream.chunksLeft).isZero();
        source.processed = 3;
        channel.updateStream();
        assertThat(source.queue).isEmpty();
    }

    @Test
    void handsCompressedStreamsToADeviceThatStreamsThem() throws Exception {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        device.streams = true;
        Channel channel = Channel.create(device);
        assertThat(channel).isNotNull();
        FakeAudio.Source source = device.sources.getFirst();
        byte[] file = {1, 2, 3};

        channel.attachBufferStream(new CompressedSoundStream(file, true));
        assertThat(source.streamed).isSameAs(file);
        assertThat(source.streamLooping).isTrue();
        assertThat(source.queue).isEmpty();

        // Nothing to top up: the device decodes as it plays.
        source.processed = 2;
        channel.updateStream();
        assertThat(source.queue).isEmpty();
        channel.destroy();
        assertThat(source.closed).isTrue();
    }

    @Test
    void destroyClosesTheStreamAndTheSourceOnce() {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        Channel channel = Channel.create(device);
        assertThat(channel).isNotNull();
        Stream stream = new Stream(10);
        channel.attachBufferStream(stream);
        channel.play();
        assertThat(channel.playing()).isTrue();

        channel.destroy();
        channel.destroy();
        FakeAudio.Source source = device.sources.getFirst();
        assertThat(stream.closed).isTrue();
        assertThat(source.closed).isTrue();
        assertThat(device.buffers).allMatch(buffer -> buffer.closed);
        assertThat(channel.stopped()).isTrue();
    }

    @Test
    void aFreshChannelIsNotStopped() {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        Channel channel = Channel.create(device);
        assertThat(channel).isNotNull();
        assertThat(channel.stopped()).isFalse();
        channel.pause(); // Only a playing channel pauses.
        assertThat(device.sources.getFirst().state).isEqualTo(FakeAudio.Source.State.INITIAL);
    }

    @Test
    void noChannelWhenTheDeviceIsOutOfSources() {
        FakeAudio.Device device = new FakeAudio.Device("d", 1);
        assertThat(Channel.create(device)).isNotNull();
        assertThat(Channel.create(device)).isNull();
    }
}
