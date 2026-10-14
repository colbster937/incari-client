package incari.platform.web.audio;

import java.util.Arrays;

/**
 * The bookkeeping of a source playing queued buffers one after another (OpenAL's buffer queue):
 * where each queued buffer starts in the stream, which ones have finished at a given position, and
 * where the queue ends. Positions are seconds of the stream's samples since the source started.
 * Plain Java; the source keeps the buffers themselves in the same order.
 */
final class StreamTimeline {

    private double[] durations = new double[8];
    private int count;
    /** Where the first queued buffer starts: the length of everything released before it. */
    private double firstStart;

    void add(double duration) {
        if (count == durations.length) {
            durations = Arrays.copyOf(durations, count * 2);
        }
        durations[count++] = duration;
    }

    int size() {
        return count;
    }

    /** Where queued buffer {@code index} starts. */
    double startOf(int index) {
        double start = firstStart;
        for (int i = 0; i < index; i++) {
            start += durations[i];
        }
        return start;
    }

    /** Where the last queued buffer ends: the source stops there, as OpenAL's does when its queue runs out. */
    double end() {
        return startOf(count);
    }

    /** How many queued buffers, from the first, have finished at {@code position}. */
    int finishedAt(double position) {
        double end = firstStart;
        int finished = 0;
        while (finished < count) {
            end += durations[finished];
            if (end > position) {
                break;
            }
            finished++;
        }
        return finished;
    }

    /** The queued buffer playing at {@code position}, or {@link #size} if it's past the end. */
    int indexAt(double position) {
        return finishedAt(position);
    }

    /** Drops the first {@code n} buffers; the rest keep their places in the stream. */
    void release(int n) {
        if (n <= 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            firstStart += durations[i];
        }
        System.arraycopy(durations, n, durations, 0, count - n);
        count -= n;
    }

    /** Empties the queue and starts the stream over. */
    void clear() {
        count = 0;
        firstStart = 0;
    }
}
