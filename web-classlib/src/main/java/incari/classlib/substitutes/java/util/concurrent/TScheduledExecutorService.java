package incari.classlib.substitutes.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.ScheduledExecutorService}. */
public interface TScheduledExecutorService extends TExecutorService {

    TScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    <V extends @Nullable Object> TScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    TScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    TScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
