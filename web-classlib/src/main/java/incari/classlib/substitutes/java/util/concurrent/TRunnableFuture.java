package incari.classlib.substitutes.java.util.concurrent;

import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.RunnableFuture}: a future whose {@link #run} computes its result. */
public interface TRunnableFuture<V extends @Nullable Object> extends Runnable, TFuture<V> {

    @Override
    void run();
}
