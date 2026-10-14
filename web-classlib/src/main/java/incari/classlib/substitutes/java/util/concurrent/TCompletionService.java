package incari.classlib.substitutes.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.CompletionService}: futures handed back in completion order. */
public interface TCompletionService<V extends @Nullable Object> {

    TFuture<V> submit(Callable<V> task);

    TFuture<V> submit(Runnable task, V result);

    TFuture<V> take() throws InterruptedException;

    @Nullable
    TFuture<V> poll();

    @Nullable
    TFuture<V> poll(long timeout, TimeUnit unit) throws InterruptedException;
}
