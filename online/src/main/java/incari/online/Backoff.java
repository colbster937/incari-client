package incari.online;

import java.util.function.DoubleSupplier;

/**
 * How long to wait before trying the service again (ADR 0058): 1 second after the first failure,
 * doubling to 5 minutes, each wait between half and all of that so a restarted service isn't met by
 * every client at once. A connection that stays up a minute counts as a success and starts over.
 */
final class Backoff {

    static final long FIRST_MILLIS = 1_000;
    static final long MAX_MILLIS = 5 * 60_000;
    /** How long a connection must last before its drop counts as a fresh start. */
    static final long STEADY_MILLIS = 60_000;

    private final DoubleSupplier random;
    private int failures;
    private long connectedAt = -1;

    /** @param random numbers in [0, 1), for the jitter */
    Backoff(DoubleSupplier random) {
        this.random = random;
    }

    /** The connection is up, as of {@code now}. */
    void connected(long now) {
        connectedAt = now;
    }

    /** The connection failed or dropped at {@code now}: how many milliseconds to wait before the next try. */
    long failed(long now) {
        if (connectedAt >= 0 && now - connectedAt >= STEADY_MILLIS) {
            failures = 0;
        }
        connectedAt = -1;
        long ceiling = failures >= 20 ? MAX_MILLIS : Math.min(MAX_MILLIS, FIRST_MILLIS << failures);
        failures++;
        return ceiling / 2 + (long) (random.getAsDouble() * (ceiling - ceiling / 2));
    }

    /** Starts over: the player asked to try now. */
    void reset() {
        failures = 0;
    }
}
