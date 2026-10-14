package com.mojang.blaze3d.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.platform.api.audio.DecodedAudio;
import incari.platform.api.audio.PcmFormat;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.Test;

class SoundBufferTest {

    private static final AudioFormat MONO16 = new AudioFormat(44100, 16, 1, true, false);

    @Test
    void mapsPcmFormats() {
        assertThat(SoundBuffer.toPcmFormat(MONO16)).isEqualTo(new PcmFormat(44100, 1, 16));
        assertThat(SoundBuffer.toPcmFormat(new AudioFormat(22050, 8, 2, false, false)))
                .isEqualTo(new PcmFormat(22050, 2, 8));
        assertThat(SoundBuffer.toPcmFormat(new AudioFormat(48000, 16, 2, true, false)))
                .isEqualTo(new PcmFormat(48000, 2, 16));
    }

    @Test
    void rejectsWhatThePlatformCantPlay() {
        AudioFormat ulaw = new AudioFormat(AudioFormat.Encoding.ULAW, 8000, 8, 1, 1, 8000, false);
        assertThatThrownBy(() -> SoundBuffer.toPcmFormat(ulaw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SoundBuffer.toPcmFormat(new AudioFormat(44100, 24, 1, true, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SoundBuffer.toPcmFormat(new AudioFormat(44100, 16, 6, true, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadsOnceAndDropsTheSamples() {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        SoundBuffer buffer = new SoundBuffer(ByteBuffer.allocateDirect(1000), MONO16);
        assertThat(buffer.size()).isEqualTo(1000);
        assertThat(buffer.isValid()).isTrue();

        var first = buffer.bufferOn(device);
        assertThat(first).isNotNull();
        assertThat(first.sizeInBytes()).isEqualTo(1000);
        assertThat(buffer.bufferOn(device)).isSameAs(first);
        assertThat(device.buffers).hasSize(1);

        buffer.discardAlBuffer();
        assertThat(((FakeAudio.Buffer) first).closed).isTrue();
        assertThat(buffer.isValid()).isFalse();
        assertThat(buffer.bufferOn(device)).isNull();
    }

    @Test
    void uploadsWhatThePlatformDecoded() {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        SoundBuffer buffer = new SoundBuffer(new DecodedAudio() {
            @Override
            public int sampleRate() {
                return 48000;
            }

            @Override
            public int channels() {
                return 2;
            }

            @Override
            public int frames() {
                return 100;
            }

            @Override
            public int sizeInBytes() {
                return 800;
            }
        });
        assertThat(buffer.size()).isEqualTo(800);
        assertThat(buffer.format().getEncoding()).isEqualTo(AudioFormat.Encoding.PCM_FLOAT);
        assertThat(buffer.format().getChannels()).isEqualTo(2);
        assertThat(buffer.format().getFrameSize()).isEqualTo(8);
        assertThat(buffer.isValid()).isTrue();

        var uploaded = buffer.bufferOn(device);
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.format()).isEqualTo(new PcmFormat(48000, 2, 16));
        assertThat(buffer.bufferOn(device)).isSameAs(uploaded);
        buffer.discardAlBuffer();
        assertThat(buffer.isValid()).isFalse();
    }

    @Test
    void aFailedUploadKeepsTheSamplesForARetry() {
        FakeAudio.Device device = new FakeAudio.Device("d", 30);
        device.failUploads = true;
        SoundBuffer buffer = new SoundBuffer(ByteBuffer.allocateDirect(8), MONO16);
        assertThat(buffer.bufferOn(device)).isNull();
        assertThat(buffer.isValid()).isTrue();

        device.failUploads = false;
        assertThat(buffer.bufferOn(device)).isNotNull();
    }

    @Test
    void wontHandOutAnotherDevicesBuffer() {
        SoundBuffer buffer = new SoundBuffer(ByteBuffer.allocateDirect(8), MONO16);
        buffer.bufferOn(new FakeAudio.Device("a", 30));
        assertThat(buffer.bufferOn(new FakeAudio.Device("b", 30))).isNull();
    }
}
