package incari.classlib.substitutes.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/** {@code java.util.concurrent.Delayed}: something that becomes due after a delay. */
public interface TDelayed extends Comparable<TDelayed> {

    /** The time left until this is due; zero or negative once it is. */
    long getDelay(TimeUnit unit);
}
