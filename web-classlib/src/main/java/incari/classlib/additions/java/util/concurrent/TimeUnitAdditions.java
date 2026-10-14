package incari.classlib.additions.java.util.concurrent;

import incari.classlib.InstanceMethod;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** {@code java.util.concurrent.TimeUnit} methods TeaVM lacks. */
public final class TimeUnitAdditions {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private TimeUnitAdditions() {}

    /**
     * {@code duration} in {@code self}'s unit, truncated toward zero and saturated at
     * {@code Long.MIN_VALUE} or {@code Long.MAX_VALUE}, as {@code TimeUnit.convert(Duration)}.
     */
    @InstanceMethod
    public static long convert(TimeUnit self, Duration duration) {
        long seconds = duration.getSeconds();
        long nanos = duration.getNano();
        // Negative durations keep a positive nano part; move it so both parts share a sign and
        // each converts toward zero.
        if (seconds < 0 && nanos > 0) {
            seconds++;
            nanos -= NANOS_PER_SECOND;
        }
        long whole = self.convert(seconds, TimeUnit.SECONDS);
        if (whole == Long.MAX_VALUE || whole == Long.MIN_VALUE) {
            return whole;
        }
        long part = self.convert(nanos, TimeUnit.NANOSECONDS);
        long sum = whole + part;
        // Overflow happened if both operands share a sign the sum doesn't.
        if (((whole ^ sum) & (part ^ sum)) < 0) {
            return whole < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }
}
