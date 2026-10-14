package incari.platform.api.rtc;

/**
 * The microphone, for voice chat. Opening it asks the user for permission, which takes a while
 * and may be refused; {@link #state} says how it went. It stays silent until {@link
 * #setTransmitting} (push to talk).
 */
public interface Microphone {

    /** Where the microphone stands. */
    enum State {
        /** Not asked for yet. */
        CLOSED,
        /** Waiting for the user's permission. */
        OPENING,
        /** Open, and sent to every peer that asked for it. */
        OPEN,
        /** The user refused, or there's no microphone. */
        UNAVAILABLE
    }

    State state();

    /** Asks for the microphone, if it isn't open or being opened. Returns at once. */
    void open();

    /**
     * Lets the microphone go: the device stops recording (the browser's recording indicator goes
     * out), peers it was sent to get silence, and {@link #state} is {@link State#CLOSED} again, so
     * {@link #open} asks anew. One still waiting for permission is let go as soon as it opens.
     * Returns at once; game thread.
     */
    void close();

    /** Whether what the microphone hears is sent now. Starts false. */
    void setTransmitting(boolean transmitting);

    /** The microphone's gain, 1 for as heard. */
    void setVolume(float volume);

    /** How loud the microphone is now, from 0 to 1, for a talking indicator. */
    float level();
}
