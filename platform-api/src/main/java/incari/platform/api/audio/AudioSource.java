package incari.platform.api.audio;

/**
 * One playing sound. It plays either a single buffer ({@link #setBuffer}) or a stream of buffers
 * queued one after another ({@link #queue}), never both.
 */
public interface AudioSource extends AutoCloseable {

    /** Where a source is in its life. */
    enum State {
        /** Created or rewound, never played. */
        INITIAL,
        PLAYING,
        PAUSED,
        /** Played to the end, or stopped. */
        STOPPED
    }

    void play();

    void pause();

    /** Stops and rewinds. */
    void stop();

    State state();

    void setPosition(double x, double y, double z);

    /** Playback speed and pitch, 1 for normal. */
    void setPitch(float pitch);

    void setLooping(boolean looping);

    /** Volume, 0 to 1. */
    void setGain(float gain);

    /**
     * Whether the position is relative to the listener rather than the world. Relative sources at
     * the origin sound the same wherever the player is: music, UI sounds.
     */
    void setRelative(boolean relative);

    /** Plays at full volume at any distance. */
    void disableAttenuation();

    /** Fades linearly from full volume at the source to silence at {@code maxDistance} blocks. */
    void setLinearAttenuation(float maxDistance);

    /** Plays this buffer, replacing whatever was attached. */
    void setBuffer(AudioBuffer buffer);

    /** Appends a buffer to the stream; it plays after the ones already queued. */
    void queue(AudioBuffer buffer);

    /**
     * Removes the queued buffers that finished playing and closes them.
     *
     * @return how many were removed
     */
    int releaseProcessedBuffers();

    /**
     * Plays a whole Ogg Vorbis file as a stream, decoded as it plays, instead of queued buffers
     * (music, records). Only on devices that {@link AudioDevice#streamsOggVorbis}; then
     * {@link #releaseProcessedBuffers} has nothing to release.
     *
     * @param file the complete file; the source keeps its own copy
     * @param looping whether to start over at the end, without a gap
     * @throws AudioException if the device can't
     */
    default void streamOggVorbis(byte[] file, boolean looping) {
        throw new AudioException("This device doesn't stream compressed files");
    }

    /** Stops the source and frees it. Queued buffers are closed with it. */
    @Override
    void close();
}
