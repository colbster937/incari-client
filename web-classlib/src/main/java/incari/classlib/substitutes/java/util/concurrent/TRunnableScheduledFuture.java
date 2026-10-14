package incari.classlib.substitutes.java.util.concurrent;

import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.RunnableScheduledFuture}. */
public interface TRunnableScheduledFuture<V extends @Nullable Object> extends TRunnableFuture<V>, TScheduledFuture<V> {

    /** Whether this task runs repeatedly. */
    boolean isPeriodic();
}
