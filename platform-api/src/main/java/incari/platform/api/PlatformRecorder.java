package incari.platform.api;

import org.jspecify.annotations.Nullable;

/**
 * Screen recording (ADR 0062), as Eaglercraft's browser builds have it: the game's picture and
 * sound, and the microphone if asked, recorded to a video the player downloads when it stops. Only
 * the browser has it; the default says there's none.
 *
 * <p>Render thread. Starting may wait on the page (the microphone's permission): {@link #state()}
 * says when it's recording.
 */
public interface PlatformRecorder {

    /** No recorder: the desktop, which has its own screen recorders. */
    PlatformRecorder NONE = new PlatformRecorder() {
        @Override
        public boolean supported() {
            return false;
        }

        @Override
        public void start(Options options, String fileName) {}

        @Override
        public void stop() {}

        @Override
        public State state() {
            return State.IDLE;
        }

        @Override
        public @Nullable String error() {
            return null;
        }

        @Override
        public long elapsedMillis() {
            return 0;
        }
    };

    /**
     * How to record.
     *
     * @param framesPerSecond the video's frame rate
     * @param videoKilobits the video's bit rate, in kilobits a second
     * @param gameVolume the game's sound in the recording, 0 to 1 (as the player hears it, times this)
     * @param microphone whether to record the microphone too
     * @param microphoneVolume the microphone's level in the recording, 0 to 1
     */
    record Options(
            int framesPerSecond, int videoKilobits, float gameVolume, boolean microphone, float microphoneVolume) {}

    /** Where a recording is. */
    enum State {
        /** Not recording. */
        IDLE,
        /** Asked to start: waiting on the page (the microphone's permission). */
        STARTING,
        /** Recording. */
        RECORDING
    }

    /** Whether this platform can record. */
    boolean supported();

    /**
     * Starts recording. When it stops, the video downloads as {@code fileName} with the format's
     * extension ({@code .mp4} or {@code .webm}, whichever the browser records).
     */
    void start(Options options, String fileName);

    /** Stops recording and downloads what was recorded. */
    void stop();

    /** Where the recording is now. */
    State state();

    /** Why the last recording failed or went without the microphone; null if it didn't. */
    @Nullable
    String error();

    /** How long the recording has run, in milliseconds; 0 when not recording. */
    long elapsedMillis();
}
