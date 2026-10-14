package incari.classlib.substitutes.java.util.concurrent;

import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.ScheduledFuture}: the result of a delayed or periodic task. */
public interface TScheduledFuture<V extends @Nullable Object> extends TDelayed, TFuture<V> {}
