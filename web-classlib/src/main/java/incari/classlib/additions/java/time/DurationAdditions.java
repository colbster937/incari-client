package incari.classlib.additions.java.time;

import incari.classlib.InstanceMethod;
import java.time.Duration;

/** {@code java.time.Duration} methods newer than ThreeTen Backport, which the browser build uses. */
public final class DurationAdditions {

    private DurationAdditions() {}

    @InstanceMethod
    public static boolean isPositive(Duration self) {
        return !self.isNegative() && !self.isZero();
    }

    /** The whole seconds, as {@code getSeconds} (the game's world-unload telemetry). */
    @InstanceMethod
    @SuppressWarnings("JavaDurationGetSecondsToToSeconds") // This is toSeconds, for a library without it.
    public static long toSeconds(Duration self) {
        return self.getSeconds();
    }
}
