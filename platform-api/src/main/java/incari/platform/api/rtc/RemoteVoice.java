package incari.platform.api.rtc;

/**
 * How a peer's voice plays: its volume and its place left to right. The caller works out both from
 * where the peer stands (proximity chat) or keeps them fixed (global chat).
 */
public interface RemoteVoice {

    /** The voice's gain, 0 for silent, 1 for as sent. */
    void setVolume(float volume);

    /** Where the voice sits between the ears, from -1 (left) to 1 (right). */
    void setPan(float pan);

    /** How loud the peer is now, from 0 to 1: whether they're talking. */
    float level();
}
