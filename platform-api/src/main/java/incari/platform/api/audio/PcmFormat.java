package incari.platform.api.audio;

/**
 * The layout of uncompressed audio samples.
 *
 * @param sampleRate samples per second per channel, for example 44100
 * @param channels 1 (mono, the only kind that can be positioned) or 2 (stereo)
 * @param bitsPerSample 8 (unsigned) or 16 (signed, little-endian)
 */
public record PcmFormat(int sampleRate, int channels, int bitsPerSample) {

    public PcmFormat {
        if (sampleRate <= 0 || (channels != 1 && channels != 2) || (bitsPerSample != 8 && bitsPerSample != 16)) {
            throw new AudioException("Unsupported PCM format: " + sampleRate + " Hz, " + channels + " channels, "
                    + bitsPerSample + "-bit");
        }
    }

    /** The size of one frame (a sample for every channel) in bytes. */
    public int frameSize() {
        return channels * bitsPerSample / 8;
    }
}
