package incari.classlib.substitutes.java.util.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ExecutorService}, including the JDK 19 {@link #close} default. In the
 * browser its threads are coroutines, so the blocking methods suspend the caller rather than park
 * an OS thread.
 */
public interface TExecutorService extends Executor, AutoCloseable {

    void shutdown();

    List<Runnable> shutdownNow();

    boolean isShutdown();

    boolean isTerminated();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    <T extends @Nullable Object> TFuture<T> submit(Callable<T> task);

    <T extends @Nullable Object> TFuture<T> submit(Runnable task, T result);

    TFuture<?> submit(Runnable task);

    <T extends @Nullable Object> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException;

    <T extends @Nullable Object> List<TFuture<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException;

    <T extends @Nullable Object> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException;

    <T extends @Nullable Object> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TTimeoutException;

    /**
     * Shuts down and waits for the tasks already submitted to finish. An interrupt while waiting
     * switches to {@link #shutdownNow} and is re-asserted once the executor has terminated.
     */
    @Override
    default void close() {
        boolean terminated = isTerminated();
        if (terminated) {
            return;
        }
        shutdown();
        boolean interrupted = false;
        while (!terminated) {
            try {
                terminated = awaitTermination(1L, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                if (!interrupted) {
                    shutdownNow();
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
