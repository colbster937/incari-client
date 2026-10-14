package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.FutureTask}. All state sits behind one private monitor, and waiters
 * block on it with {@code wait}, which in the browser suspends the waiting coroutine.
 *
 * <p>{@code cancel(true)} interrupts the running thread while still holding the monitor, so the
 * interrupt can only land while {@link #run} is in progress; the pools clear any interrupt left
 * over before they run the next task.
 */
public class TFutureTask<V extends @Nullable Object> implements TRunnableFuture<V> {

    private static final int NEW = 0;
    private static final int NORMAL = 1;
    private static final int EXCEPTIONAL = 2;
    private static final int CANCELLED = 3;
    private static final int INTERRUPTED = 4;

    private final Object lock = new Object();
    private final Callable<V> callable;

    // Guarded by lock.
    private int state = NEW;
    private @Nullable Object outcome;
    private @Nullable Thread runner;

    public TFutureTask(Callable<V> callable) {
        this.callable = Objects.requireNonNull(callable);
    }

    public TFutureTask(Runnable runnable, V result) {
        this.callable = TExecutors.callable(runnable, result);
    }

    @Override
    public boolean isCancelled() {
        synchronized (lock) {
            return state >= CANCELLED;
        }
    }

    @Override
    public boolean isDone() {
        synchronized (lock) {
            return state != NEW;
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (lock) {
            if (state != NEW) {
                return false;
            }
            state = mayInterruptIfRunning ? INTERRUPTED : CANCELLED;
            Thread r = runner;
            if (mayInterruptIfRunning && r != null) {
                r.interrupt();
            }
            lock.notifyAll();
        }
        done();
        return true;
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        synchronized (lock) {
            Monitors.checkInterrupt();
            while (state == NEW) {
                lock.wait();
            }
            return report();
        }
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException {
        long nanos = unit.toNanos(timeout);
        synchronized (lock) {
            Monitors.checkInterrupt();
            while (state == NEW) {
                if (nanos <= 0) {
                    throw new TTimeoutException();
                }
                nanos = Monitors.awaitNanos(lock, nanos);
            }
            return report();
        }
    }

    // Caller holds lock and state isn't NEW.
    private V report() throws ExecutionException {
        if (state == NORMAL) {
            return Monitors.cast(outcome);
        }
        if (state == EXCEPTIONAL) {
            throw new ExecutionException((Throwable) Objects.requireNonNull(outcome));
        }
        throw new CancellationException();
    }

    @Override
    public V resultNow() {
        synchronized (lock) {
            return switch (state) {
                case NORMAL -> Monitors.cast(outcome);
                case EXCEPTIONAL -> throw new IllegalStateException("Task completed with exception");
                case NEW -> throw new IllegalStateException("Task has not completed");
                default -> throw new IllegalStateException("Task was cancelled");
            };
        }
    }

    @Override
    public Throwable exceptionNow() {
        synchronized (lock) {
            return switch (state) {
                case EXCEPTIONAL -> (Throwable) Objects.requireNonNull(outcome);
                case NORMAL -> throw new IllegalStateException("Task completed with a result");
                case NEW -> throw new IllegalStateException("Task has not completed");
                default -> throw new IllegalStateException("Task was cancelled");
            };
        }
    }

    @Override
    public State state() {
        synchronized (lock) {
            return switch (state) {
                case NEW -> State.RUNNING;
                case NORMAL -> State.SUCCESS;
                case EXCEPTIONAL -> State.FAILED;
                default -> State.CANCELLED;
            };
        }
    }

    /** Called once the task completes in any way, including cancellation. Does nothing by default. */
    protected void done() {}

    /** Completes with {@code v}, unless already completed or cancelled. */
    protected void set(V v) {
        if (complete(NORMAL, v)) {
            done();
        }
    }

    /** Completes with {@code t} as the failure, unless already completed or cancelled. */
    protected void setException(Throwable t) {
        if (complete(EXCEPTIONAL, t)) {
            done();
        }
    }

    private boolean complete(int newState, @Nullable Object value) {
        synchronized (lock) {
            if (state != NEW) {
                return false;
            }
            outcome = value;
            state = newState;
            lock.notifyAll();
            return true;
        }
    }

    @Override
    public void run() {
        if (!claimRunner()) {
            return;
        }
        try {
            V result;
            try {
                result = callable.call();
            } catch (Throwable t) {
                setException(t);
                return;
            }
            set(result);
        } finally {
            releaseRunner();
        }
    }

    /**
     * Runs the task without setting a result, leaving it ready to run again: for periodic tasks.
     * Returns false if it failed, was cancelled, or couldn't start.
     */
    protected boolean runAndReset() {
        if (!claimRunner()) {
            return false;
        }
        boolean ran = false;
        try {
            try {
                callable.call();
                ran = true;
            } catch (Throwable t) {
                setException(t);
            }
        } finally {
            releaseRunner();
        }
        synchronized (lock) {
            return ran && state == NEW;
        }
    }

    private boolean claimRunner() {
        synchronized (lock) {
            if (state != NEW || runner != null) {
                return false;
            }
            runner = Thread.currentThread();
            return true;
        }
    }

    private void releaseRunner() {
        synchronized (lock) {
            runner = null;
        }
    }

    @Override
    public String toString() {
        String status;
        synchronized (lock) {
            status = switch (state) {
                case NORMAL -> "[Completed normally]";
                case EXCEPTIONAL -> "[Completed exceptionally: " + outcome + "]";
                case NEW -> "[Not completed, task = " + callable + "]";
                default -> "[Cancelled]";
            };
        }
        return super.toString() + status;
    }
}
