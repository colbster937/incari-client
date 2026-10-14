package incari.classlib.substitutes.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.AbstractExecutorService}: {@code submit}, {@code invokeAll} and
 * {@code invokeAny} built on {@link #execute} and {@link #newTaskFor}.
 *
 * <p>{@code invokeAny} submits every task up front and returns the first success; in the browser
 * there's no parallelism to save by submitting lazily, and it keeps the code simple.
 */
public abstract class TAbstractExecutorService implements TExecutorService {

    public TAbstractExecutorService() {}

    /** The future {@link #submit(Runnable, Object)} runs; a {@link TFutureTask} by default. */
    protected <T extends @Nullable Object> TRunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new TFutureTask<>(runnable, value);
    }

    /** The future {@link #submit(Callable)} runs; a {@link TFutureTask} by default. */
    protected <T extends @Nullable Object> TRunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new TFutureTask<>(callable);
    }

    @Override
    public TFuture<?> submit(Runnable task) {
        Objects.requireNonNull(task);
        TRunnableFuture<@Nullable Void> future = newTaskFor(task, null);
        execute(future);
        return future;
    }

    @Override
    public <T extends @Nullable Object> TFuture<T> submit(Runnable task, T result) {
        Objects.requireNonNull(task);
        TRunnableFuture<T> future = newTaskFor(task, result);
        execute(future);
        return future;
    }

    @Override
    public <T extends @Nullable Object> TFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task);
        TRunnableFuture<T> future = newTaskFor(task);
        execute(future);
        return future;
    }

    @Override
    public <T extends @Nullable Object> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        try {
            return doInvokeAny(tasks, false, 0L);
        } catch (TTimeoutException e) {
            throw new AssertionError("an untimed invokeAny can't time out", e);
        }
    }

    @Override
    public <T extends @Nullable Object> T invokeAny(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TTimeoutException {
        return doInvokeAny(tasks, true, unit.toNanos(timeout));
    }

    private <T extends @Nullable Object> T doInvokeAny(
            Collection<? extends Callable<T>> tasks, boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TTimeoutException {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("no tasks");
        }
        long deadline = timed ? System.nanoTime() + nanos : 0L;
        TExecutorCompletionService<T> completion = new TExecutorCompletionService<>(this);
        List<TFuture<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> task : tasks) {
                futures.add(completion.submit(Objects.requireNonNull(task)));
            }
            ExecutionException failure = null;
            for (int i = 0; i < futures.size(); i++) {
                TFuture<T> done;
                if (timed) {
                    done = completion.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (done == null) {
                        throw new TTimeoutException();
                    }
                } else {
                    done = completion.take();
                }
                try {
                    return done.get();
                } catch (ExecutionException e) {
                    failure = e;
                } catch (CancellationException e) {
                    failure = new ExecutionException(e);
                }
            }
            throw Objects.requireNonNull(failure);
        } finally {
            cancelAll(futures);
        }
    }

    @Override
    public <T extends @Nullable Object> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        List<TFuture<T>> futures = new ArrayList<>(tasks.size());
        boolean finished = false;
        try {
            for (Callable<T> task : tasks) {
                TRunnableFuture<T> future = newTaskFor(Objects.requireNonNull(task));
                futures.add(future);
                execute(future);
            }
            for (TFuture<T> future : futures) {
                if (!future.isDone()) {
                    try {
                        future.get();
                    } catch (CancellationException | ExecutionException ignored) {
                        // The caller inspects each future; only completion matters here.
                    }
                }
            }
            finished = true;
            return futures;
        } finally {
            if (!finished) {
                cancelAll(futures);
            }
        }
    }

    @Override
    public <T extends @Nullable Object> List<TFuture<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<TFuture<T>> futures = new ArrayList<>(tasks.size());
        boolean finished = false;
        try {
            for (Callable<T> task : tasks) {
                futures.add(newTaskFor(Objects.requireNonNull(task)));
            }
            for (TFuture<T> future : futures) {
                if (deadline - System.nanoTime() <= 0) {
                    return futures; // finally cancels what hasn't completed
                }
                execute((Runnable) future);
            }
            for (TFuture<T> future : futures) {
                if (!future.isDone()) {
                    try {
                        future.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    } catch (CancellationException | ExecutionException ignored) {
                        // The caller inspects each future; only completion matters here.
                    } catch (TTimeoutException e) {
                        return futures;
                    }
                }
            }
            finished = true;
            return futures;
        } finally {
            if (!finished) {
                cancelAll(futures);
            }
        }
    }

    private static void cancelAll(List<? extends TFuture<?>> futures) {
        for (TFuture<?> future : futures) {
            future.cancel(true);
        }
    }
}
