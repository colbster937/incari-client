package com.mojang.blaze3d.audio;

import com.mojang.logging.LogUtils;
import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.DecodedAudio;
import incari.platform.api.audio.PcmFormat;
import java.nio.ByteBuffer;
import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari replacement: a fully decoded sound, uploaded to the device the first time a channel
 * plays it.
 *
 * <p>Decoding happens off the sound thread, before any device may be open, so the samples are
 * held here until then: PCM from the game's decoder, or what the platform's own decoder produced
 * (the browser's, ADR 0013). Once uploaded, the device copy is the only one: the samples are
 * dropped to save memory, and {@link #discardAlBuffer} ends the buffer's life.
 */
public class SoundBuffer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private @Nullable ByteBuffer data;
    private @Nullable DecodedAudio decoded;
    private final AudioFormat format;
    private final int size;
    private @Nullable AudioBuffer uploaded;
    private @Nullable AudioDevice uploadedTo;

    public SoundBuffer(final ByteBuffer data, final AudioFormat format) {
        this.data = data;
        this.format = format;
        this.size = data.limit();
    }

    /** A sound the platform decoded: 32-bit float samples, as far as the game's statistics tell. */
    public SoundBuffer(final DecodedAudio decoded) {
        this.decoded = decoded;
        int frameSize = decoded.channels() * Float.BYTES;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_FLOAT,
                decoded.sampleRate(),
                Float.SIZE,
                decoded.channels(),
                frameSize,
                decoded.sampleRate(),
                false);
        this.size = decoded.sizeInBytes();
    }

    /**
     * The device copy of these samples, uploading them on first use.
     *
     * @return null if the upload failed (logged, and retried next time), or if the samples went
     *     to another device and were dropped here, or after {@link #discardAlBuffer}
     */
    @Nullable
    AudioBuffer bufferOn(final AudioDevice device) {
        AudioBuffer buffer = uploaded;
        if (buffer != null) {
            return device.equals(uploadedTo) ? buffer : null;
        }
        ByteBuffer samples = data;
        DecodedAudio platformSamples = decoded;
        if (samples == null && platformSamples == null) {
            return null;
        }
        try {
            buffer = samples != null
                    ? device.createBuffer(samples, toPcmFormat(format))
                    : device.createBuffer(Objects.requireNonNull(platformSamples));
        } catch (AudioException | IllegalArgumentException e) {
            LOGGER.error("Couldn't upload a sound buffer: {}", e.getMessage());
            return null;
        }
        uploaded = buffer;
        uploadedTo = device;
        data = null;
        decoded = null;
        return buffer;
    }

    /** Frees the device copy. The samples were dropped when it was made, so this buffer is spent. */
    public void discardAlBuffer() {
        AudioBuffer buffer = uploaded;
        if (buffer != null) {
            buffer.close();
        }
        uploaded = null;
        uploadedTo = null;
    }

    public AudioFormat format() {
        return format;
    }

    public int size() {
        return size;
    }

    /** Whether it can still be played: the samples are held here or on a device. */
    public boolean isValid() {
        return data != null || decoded != null || uploaded != null;
    }

    /**
     * The platform's description of {@code format}. Only uncompressed PCM, mono or stereo, 8 or
     * 16 bits: what the game's Ogg decoder produces.
     *
     * @throws IllegalArgumentException for anything else
     */
    static PcmFormat toPcmFormat(final AudioFormat format) {
        AudioFormat.Encoding encoding = format.getEncoding();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(encoding) && !AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)) {
            throw new IllegalArgumentException("Not uncompressed PCM: " + format);
        }
        try {
            return new PcmFormat((int) format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());
        } catch (AudioException e) {
            throw new IllegalArgumentException("Unsupported audio format: " + format, e);
        }
    }
}
