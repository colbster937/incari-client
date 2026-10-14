package incari.platform.api.audio;

/**
 * Decodes compressed sound files the platform can decode better than the game's own Java decoder:
 * in the browser, natively and off the page's thread. A platform without one ({@link
 * PlatformAudio#decoder} is null) leaves decoding to the game.
 */
public interface AudioDecoder {

    /**
     * Decodes a whole Ogg Vorbis file into samples any of this platform's devices can play
     * ({@link AudioDevice#createBuffer(DecodedAudio)}).
     *
     * <p>Waits until the file is decoded: never call it from the game's main thread or its sound
     * thread. In the browser the wait suspends only the calling coroutine; the page keeps running.
     *
     * @param file the complete file
     * @throws AudioException if it isn't a file this decoder can read
     */
    DecodedAudio decodeOggVorbis(byte[] file);
}
