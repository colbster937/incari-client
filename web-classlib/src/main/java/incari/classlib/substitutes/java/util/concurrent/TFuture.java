package incari.classlib.substitutes.java.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.Future}, including the JDK 19 {@code resultNow}, {@code exceptionNow}
 * and {@code state} defaults. In the browser, {@code get} suspends the calling coroutine until the
 * task completes on another one.
 */
public interface TFuture<V extends @Nullable Object> {

    boolean cancel(boolean mayInterruptIfRunning);

    boolean isCancelled();

    boolean isDone();

    V get() throws InterruptedException, ExecutionException;

    V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException;

    /** The result of a task known to have completed normally; {@link IllegalStateException} otherwise. */
    default V resultNow() {
        if (!isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return get();
                } catch (InterruptedException e) {
                    // A completed task's get doesn't block, but it may still see the flag: retry,
                    // and restore the flag afterwards.
                    interrupted = true;
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Task completed with exception");
                } catch (CancellationException e) {
                    throw new IllegalStateException("Task was cancelled");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The exception of a task known to have failed; {@link IllegalStateException} otherwise. */
    default Throwable exceptionNow() {
        if (!isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        if (isCancelled()) {
            throw new IllegalStateException("Task was cancelled");
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    get();
                    throw new IllegalStateException("Task completed with a result");
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    return cause != null ? cause : e;
                } catch (CancellationException e) {
                    throw new IllegalStateException("Task was cancelled");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Where the task is in its life. */
    default State state() {
        if (!isDone()) {
            return State.RUNNING;
        }
        if (isCancelled()) {
            return State.CANCELLED;
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    get();
                    return State.SUCCESS;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    return State.FAILED;
                } catch (CancellationException e) {
                    return State.CANCELLED;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@code java.util.concurrent.Future.State}. */
    enum State {
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }
}
