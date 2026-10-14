package incari.platform.api.audio;

/**
 * A sound an {@link AudioDecoder} decoded, held by the platform in whatever form its devices play
 * (in the browser, a Web Audio buffer of 32-bit float samples). It isn't tied to a device: it can
 * be decoded before one opens and given to the next one after it closes.
 */
public interface DecodedAudio {

    /** Samples per second per channel. */
    int sampleRate();

    /** 1 (mono, the only kind that can be positioned) or 2 (stereo). */
    int channels();

    /** How many samples each channel has. */
    int frames();

    /** How much memory the samples take, in bytes. */
    int sizeInBytes();
}
