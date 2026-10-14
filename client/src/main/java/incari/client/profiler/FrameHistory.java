package incari.client.profiler;

import java.util.Arrays;

/**
 * The last frames' lengths, for the overlay's graph and its frame rates. Pure logic, tested
 * on a JVM; allocates only when asked for a percentile.
 */
final class FrameHistory {

    private final double[] millis;
    private int next;
    private int size;

    FrameHistory(int capacity) {
        this.millis = new double[capacity];
    }

    void add(double frameMillis) {
        millis[next] = frameMillis;
        next = (next + 1) % millis.length;
        size = Math.min(size + 1, millis.length);
    }

    int size() {
        return size;
    }

    int capacity() {
        return millis.length;
    }

    /** The {@code age}th most recent frame's length: 0 is the newest. */
    double ago(int age) {
        if (age < 0 || age >= size) {
            throw new IndexOutOfBoundsException(age);
        }
        return millis[Math.floorMod(next - 1 - age, millis.length)];
    }

    /** Frames a second over the frames that fit in the last {@code windowMillis}; 0 if none. */
    double framesPerSecond(double windowMillis) {
        double total = 0;
        int frames = 0;
        while (frames < size && total < windowMillis) {
            total += ago(frames);
            frames++;
        }
        return total > 0 ? frames * 1000.0 / total : 0;
    }

    /**
     * The mean length of the slowest {@code percent}% of the kept frames (at least one): at 1, the
     * frame time of the "1% low", as the benchmark reports it. NaN if there are none.
     */
    double slowestMean(double percent) {
        if (size == 0) {
            return Double.NaN;
        }
        double[] sorted = new double[size];
        for (int i = 0; i < size; i++) {
            sorted[i] = ago(i);
        }
        Arrays.sort(sorted);
        int count = Math.max(1, (int) Math.floor(size * percent / 100));
        double total = 0;
        for (int i = size - count; i < size; i++) {
            total += sorted[i];
        }
        return total / count;
    }
}
