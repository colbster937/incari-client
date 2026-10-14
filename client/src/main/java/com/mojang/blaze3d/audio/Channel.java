package com.mojang.blaze3d.audio;

import com.mojang.logging.LogUtils;
import incari.client.audio.CompressedSoundStream;
import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.AudioSource;
import incari.platform.api.audio.PcmFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari replacement: one playing sound, over a platform {@link AudioSource}.
 *
 * <p>It plays either a whole decoded {@link SoundBuffer} or an {@link AudioStream} (music, long
 * records), which is decoded a second at a time into a short queue of buffers and topped up by
 * {@link #updateStream} as they finish; or, on a device that streams compressed files itself (the
 * browser's), is handed over whole. All calls come from the sound thread.
 */
public class Channel {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Buffers kept queued ahead while streaming: enough to ride out a slow sound-thread tick. */
    private static final int STREAM_QUEUE_LENGTH = 4;

    public static final int BUFFER_DURATION_SECONDS = 1;

    private final AudioDevice device;
    private final AudioSource source;
    private final AtomicBoolean initialized = new AtomicBoolean(true);
    private @Nullable AudioStream stream;
    private @Nullable PcmFormat streamFormat;
    private int streamChunkBytes;

    /** A new channel on {@code device}, or null if it has no free source. */
    static @Nullable Channel create(final AudioDevice device) {
        AudioSource source = device.createSource();
        return source == null ? null : new Channel(device, source);
    }

    private Channel(final AudioDevice device, final AudioSource source) {
        this.device = device;
        this.source = source;
    }

    /** Stops, closes the stream if any, and frees the source. Only the first call does anything. */
    public void destroy() {
        if (!initialized.compareAndSet(true, false)) {
            return;
        }
        source.stop();
        AudioStream current = stream;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close audio stream", e);
            }
            stream = null;
        }
        // Frees the queued stream buffers too. A static buffer belongs to its SoundBuffer.
        source.close();
    }

    public void play() {
        if (initialized.get()) {
            source.play();
        }
    }

    private AudioSource.State state() {
        return initialized.get() ? source.state() : AudioSource.State.STOPPED;
    }

    public void pause() {
        if (state() == AudioSource.State.PLAYING) {
            source.pause();
        }
    }

    public void unpause() {
        if (state() == AudioSource.State.PAUSED) {
            source.play();
        }
    }

    public void stop() {
        if (initialized.get()) {
            source.stop();
        }
    }

    public boolean playing() {
        return state() == AudioSource.State.PLAYING;
    }

    /**
     * Whether it has finished (or was destroyed). A channel that was set up but never played is
     * not stopped, so the game doesn't reclaim it before its first {@link #play}.
     */
    public boolean stopped() {
        return state() == AudioSource.State.STOPPED;
    }

    public void setSelfPosition(final Vec3 newPosition) {
        source.setPosition(newPosition.x, newPosition.y, newPosition.z);
    }

    public void setPitch(final float pitch) {
        source.setPitch(pitch);
    }

    public void setLooping(final boolean looping) {
        source.setLooping(looping);
    }

    public void setVolume(final float volume) {
        source.setGain(volume);
    }

    public void disableAttenuation() {
        source.disableAttenuation();
    }

    public void linearAttenuation(final float maxDistance) {
        source.setLinearAttenuation(maxDistance);
    }

    public void setRelative(final boolean relative) {
        source.setRelative(relative);
    }

    public void attachStaticBuffer(final SoundBuffer buffer) {
        AudioBuffer uploaded = buffer.bufferOn(device);
        if (uploaded != null) {
            source.setBuffer(uploaded);
        }
    }

    public void attachBufferStream(final AudioStream stream) {
        if (stream instanceof CompressedSoundStream compressed && device.streamsOggVorbis()) {
            this.stream = stream;
            try {
                source.streamOggVorbis(compressed.file(), compressed.looping());
            } catch (AudioException e) {
                LOGGER.error("Can't stream audio: {}", e.getMessage());
            }
            return;
        }
        AudioFormat format = stream.getFormat();
        this.stream = stream;
        this.streamChunkBytes = bytesPerSeconds(format, BUFFER_DURATION_SECONDS);
        try {
            this.streamFormat = SoundBuffer.toPcmFormat(format);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Can't stream audio: {}", e.getMessage());
            this.streamFormat = null;
            return;
        }
        queueChunks(STREAM_QUEUE_LENGTH);
    }

    /** How many bytes of {@code format} last {@code seconds}. */
    static int bytesPerSeconds(final AudioFormat format, final int seconds) {
        float bytesPerSample = format.getSampleSizeInBits() / 8.0F;
        return (int) (seconds * format.getSampleRate() * format.getChannels() * bytesPerSample);
    }

    /** Refills the stream's queue with as many chunks as finished playing since the last call. */
    public void updateStream() {
        if (stream != null) {
            queueChunks(source.releaseProcessedBuffers());
        }
    }

    /** Decodes up to {@code count} more chunks of the stream and queues them. */
    private void queueChunks(final int count) {
        AudioStream current = stream;
        PcmFormat format = streamFormat;
        if (current == null || format == null) {
            return;
        }
        try {
            for (int i = 0; i < count; i++) {
                ByteBuffer chunk = current.read(streamChunkBytes);
                // The decoder hands back an empty buffer at the end; queueing nothing is fine.
                if (!chunk.hasRemaining()) {
                    continue;
                }
                source.queue(device.createBuffer(chunk, format));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read from audio stream", e);
        } catch (AudioException e) {
            LOGGER.error("Failed to queue audio stream data: {}", e.getMessage());
        }
    }
}
