package incari.platform.web.audio;

/**
 * Where a source is in its own samples, in seconds, worked out from the audio context's clock:
 * Web Audio's buffer sources can't pause or report their position, so a pause is a stop that
 * remembers where it was, and a resume starts a new node there. The rate is the source's pitch,
 * which scales how fast it moves through its samples. Plain Java.
 */
final class PlaybackClock {

    private double anchorTime;
    private double anchorPosition;
    private double rate = 1;
    private boolean running;

    /** Starts (or keeps) moving from the current position at {@code now}. */
    void start(double now) {
        if (!running) {
            anchorTime = now;
            running = true;
        }
    }

    /** Stops moving, keeping the position. */
    void pause(double now) {
        anchorPosition = position(now);
        running = false;
    }

    /** Back to the start, stopped. */
    void reset() {
        anchorPosition = 0;
        running = false;
    }

    /** Changes the speed from {@code now} on; the position so far is kept. */
    void setRate(double now, double newRate) {
        anchorPosition = position(now);
        anchorTime = now;
        rate = newRate;
    }

    /** Moves to {@code position}, as of {@code now}. */
    void seek(double now, double position) {
        anchorPosition = position;
        anchorTime = now;
    }

    double rate() {
        return rate;
    }

    boolean running() {
        return running;
    }

    /** The position at context time {@code now}. */
    double position(double now) {
        return running ? anchorPosition + Math.max(0, now - anchorTime) * rate : anchorPosition;
    }

    /** The position within a looping sound of {@code duration} seconds. */
    static double wrap(double position, double duration) {
        if (duration <= 0) {
            return 0;
        }
        double wrapped = position % duration;
        return wrapped < 0 ? wrapped + duration : wrapped;
    }
}
