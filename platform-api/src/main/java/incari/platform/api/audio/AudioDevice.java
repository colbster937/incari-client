package incari.platform.api.audio;

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * An open output device and its mixer. Everything it creates belongs to it and becomes invalid
 * when it's closed. Call it from the game's sound thread.
 */
public interface AudioDevice extends AutoCloseable {

    /** The device's name, as {@link AudioDeviceList} lists it. */
    String name();

    /** Whether the device was unplugged or otherwise lost since it was opened. */
    boolean isDisconnected();

    /** How many sources can play at once, at least 1. */
    int maxSources();

    /** The listener: the player's ears. */
    AudioListener listener();

    /** Creates a source, or returns null if {@link #maxSources} are already in use. */
    @Nullable
    AudioSource createSource();

    /**
     * Uploads PCM samples into a buffer sources can play.
     *
     * @param samples interleaved PCM, little-endian for 16-bit; read from its position to its limit
     * @throws AudioException if the format isn't supported or the upload failed
     */
    AudioBuffer createBuffer(ByteBuffer samples, PcmFormat format);

    /**
     * A buffer over samples the platform's {@link AudioDecoder} produced. Only platforms with a
     * decoder support it.
     *
     * @throws AudioException if this device can't play them
     */
    default AudioBuffer createBuffer(DecodedAudio audio) {
        throw new AudioException("This device only plays PCM it's given");
    }

    /**
     * Whether its sources can play an Ogg Vorbis file as a stream, decoding it as they play
     * ({@link AudioSource#streamOggVorbis}), rather than being queued decoded chunks.
     */
    default boolean streamsOggVorbis() {
        return false;
    }

    /** Closes the device and everything it created. */
    @Override
    void close();
}
