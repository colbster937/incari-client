package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ForkJoinTask}, in a deliberately simple form.
 *
 * <p>{@link #fork} puts the task in its pool's queue (the current worker's pool, else the common
 * pool). Whoever gets to a task first claims it: a worker taking it from the queue, or a thread
 * that {@link #join}s (or {@link #invoke}s, or untimed-{@link #get}s) it before any worker has, which
 * then runs it inline. A worker that finds an already-claimed task in the queue skips it. Joining a
 * task another thread is running waits on the task's monitor. Because a joiner runs unstarted work
 * itself, recursive divide-and-conquer can't deadlock however few workers there are, and in the
 * browser, where the workers are coroutines on one thread anyway, most of the work runs inline
 * without a coroutine switch.
 */
public abstract class TForkJoinTask<V extends @Nullable Object> implements TFuture<V> {

    private static final int NEW = 0;
    private static final int RUNNING = 1;
    private static final int NORMAL = 2;
    private static final int EXCEPTIONAL = 3;
    private static final int CANCELLED = 4;

    // Guarded by this task's monitor.
    private int status = NEW;
    private @Nullable Throwable exception;
    private short tag;

    public TForkJoinTask() {}

    // ---------------------------------------------------------------------------------------------
    // Abstract operations

    /** The result, or {@code null} while there isn't one. */
    public abstract V getRawResult();

    /** Sets the result, for {@link #complete}. */
    protected abstract void setRawResult(V value);

    /** Runs the task's body; returns whether it has completed normally. */
    protected abstract boolean exec();

    // ---------------------------------------------------------------------------------------------
    // Claiming and running

    /** Takes the right to run this task, if nobody has it yet. */
    final synchronized boolean tryClaim() {
        if (status != NEW) {
            return false;
        }
        status = RUNNING;
        return true;
    }

    /** Whether nobody has claimed, run, completed or cancelled this task yet. */
    final synchronized boolean isUnclaimed() {
        return status == NEW;
    }

    /** Runs a claimed task and returns what its body threw, if anything. */
    final @Nullable Throwable runClaimed() {
        boolean completed;
        try {
            completed = exec();
        } catch (Throwable t) {
            finish(EXCEPTIONAL, t);
            return t;
        }
        if (completed) {
            finish(NORMAL, null);
        }
        return null;
    }

    /** Whether a failure of this task should end the worker that ran it (execute(Runnable) tasks). */
    boolean failsWorker() {
        return false;
    }

    private synchronized boolean finish(int newStatus, @Nullable Throwable t) {
        if (status >= NORMAL) {
            return false;
        }
        status = newStatus;
        exception = t;
        notifyAll();
        return true;
    }

    private void runIfUnclaimed() {
        if (tryClaim()) {
            runClaimed();
        }
    }

    private synchronized void awaitDoneUninterruptibly() {
        boolean interrupted = false;
        while (status < NORMAL) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void awaitDone() throws InterruptedException {
        Monitors.checkInterrupt();
        while (status < NORMAL) {
            wait();
        }
    }

    // Returns whether the task completed in time.
    private synchronized boolean awaitDone(long nanos) throws InterruptedException {
        Monitors.checkInterrupt();
        while (status < NORMAL) {
            if (nanos <= 0) {
                return false;
            }
            nanos = Monitors.awaitNanos(this, nanos);
        }
        return true;
    }

    /** Waits for the task without running it here: for a pool's invoke from outside the pool. */
    final void awaitWithoutRunning() {
        awaitDoneUninterruptibly();
    }

    // The result of a completed task, as join and invoke report it.
    final V reportJoin() {
        int s;
        Throwable t;
        synchronized (this) {
            s = status;
            t = exception;
        }
        if (s == CANCELLED) {
            throw new CancellationException();
        }
        if (s == EXCEPTIONAL && t != null) {
            throw Monitors.rethrow(t);
        }
        return getRawResult();
    }

    // ---------------------------------------------------------------------------------------------
    // Forking and joining

    /** Queues this task in the current worker's pool, or the common pool outside one. */
    public final TForkJoinTask<V> fork() {
        TForkJoinPool pool = getPool();
        (pool != null ? pool : TForkJoinPool.commonPool()).push(this);
        return this;
    }

    /** The result once done, running the task here if no one has started it. */
    public final V join() {
        runIfUnclaimed();
        awaitDoneUninterruptibly();
        return reportJoin();
    }

    /** Runs the task here (unless someone already has) and returns its result. */
    public final V invoke() {
        return join();
    }

    public static void invokeAll(TForkJoinTask<?> t1, TForkJoinTask<?> t2) {
        t2.fork();
        t1.quietlyInvoke();
        t2.quietlyJoin();
        t1.reportJoin();
        t2.reportJoin();
    }

    public static void invokeAll(TForkJoinTask<?>... tasks) {
        for (int i = tasks.length - 1; i > 0; i--) {
            Objects.requireNonNull(tasks[i]).fork();
        }
        if (tasks.length > 0) {
            Objects.requireNonNull(tasks[0]).quietlyInvoke();
        }
        for (TForkJoinTask<?> task : tasks) {
            task.quietlyJoin();
        }
        for (TForkJoinTask<?> task : tasks) {
            task.reportJoin();
        }
    }

    public static <T extends TForkJoinTask<?>> Collection<T> invokeAll(Collection<T> tasks) {
        invokeAll(tasks.toArray(new TForkJoinTask<?>[0]));
        return tasks;
    }

    /** Cancels the task if nobody has started it; true if it is (now) cancelled. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (this) {
            if (status == NEW) {
                status = CANCELLED;
                notifyAll();
            }
            return status == CANCELLED;
        }
    }

    @Override
    public final synchronized boolean isDone() {
        return status >= NORMAL;
    }

    @Override
    public final synchronized boolean isCancelled() {
        return status == CANCELLED;
    }

    public final synchronized boolean isCompletedAbnormally() {
        return status >= EXCEPTIONAL;
    }

    public final synchronized boolean isCompletedNormally() {
        return status == NORMAL;
    }

    /** What the task failed with, a CancellationException if cancelled, else {@code null}. */
    public final synchronized @Nullable Throwable getException() {
        if (status == CANCELLED) {
            return new CancellationException();
        }
        return status == EXCEPTIONAL ? exception : null;
    }

    /** Completes the task with {@code ex} (a checked one wrapped in a RuntimeException). */
    public void completeExceptionally(Throwable ex) {
        finish(EXCEPTIONAL, ex instanceof RuntimeException || ex instanceof Error ? ex : new RuntimeException(ex));
    }

    /** Completes the task with {@code value}, whether or not it has run. */
    public void complete(V value) {
        setRawResult(value);
        finish(NORMAL, null);
    }

    /** Completes the task normally without setting a result. */
    public final void quietlyComplete() {
        finish(NORMAL, null);
    }

    @Override
    public final V get() throws InterruptedException, ExecutionException {
        runIfUnclaimed();
        awaitDone();
        return reportGet();
    }

    @Override
    public final V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException {
        if (!awaitDone(unit.toNanos(timeout))) {
            throw new TTimeoutException();
        }
        return reportGet();
    }

    private V reportGet() throws ExecutionException {
        int s;
        Throwable t;
        synchronized (this) {
            s = status;
            t = exception;
        }
        if (s == CANCELLED) {
            throw new CancellationException();
        }
        if (s == EXCEPTIONAL) {
            throw new ExecutionException(t);
        }
        return getRawResult();
    }

    @Override
    public V resultNow() {
        synchronized (this) {
            if (status == NORMAL) {
                return getRawResult();
            }
            throw new IllegalStateException(
                    status < NORMAL ? "Task has not completed" : "Task did not complete normally");
        }
    }

    @Override
    public Throwable exceptionNow() {
        synchronized (this) {
            Throwable t = exception;
            if (status == EXCEPTIONAL && t != null) {
                return t;
            }
            throw new IllegalStateException(status < NORMAL ? "Task has not completed" : "Task did not fail");
        }
    }

    @Override
    public State state() {
        synchronized (this) {
            return switch (status) {
                case NORMAL -> State.SUCCESS;
                case EXCEPTIONAL -> State.FAILED;
                case CANCELLED -> State.CANCELLED;
                default -> State.RUNNING;
            };
        }
    }

    /** Waits for the task (running it here if unstarted) without reporting its outcome. */
    public final void quietlyJoin() {
        runIfUnclaimed();
        awaitDoneUninterruptibly();
    }

    /** Runs the task here (unless someone already has) without reporting its outcome. */
    public final void quietlyInvoke() {
        quietlyJoin();
    }

    /** Waits up to the timeout for the task, without running it here; true if it completed. */
    public final boolean quietlyJoin(long timeout, TimeUnit unit) throws InterruptedException {
        return awaitDone(unit.toNanos(timeout));
    }

    /** {@link #quietlyJoin(long, TimeUnit)}, ignoring interrupts (re-asserted on return). */
    public final boolean quietlyJoinUninterruptibly(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return awaitDone(deadline - System.nanoTime());
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Resets the task so it can be forked again. */
    public synchronized void reinitialize() {
        status = NEW;
        exception = null;
    }

    /**
     * Takes the task back out of its pool's queue if no one has started it, so the caller can run it
     * (with {@link #invoke}, say). True if it was still queued.
     */
    public boolean tryUnfork() {
        TForkJoinPool pool = getPool();
        return (pool != null ? pool : TForkJoinPool.commonPool()).tryRemove(this);
    }

    // ---------------------------------------------------------------------------------------------
    // Pool queries

    /**
     * Runs queued tasks of the current worker's pool here until its queue is empty. Unlike the JDK's
     * it doesn't also wait for tasks other workers are running. Outside a pool it waits for the
     * common pool to go quiet.
     */
    public static void helpQuiesce() {
        TForkJoinPool pool = getPool();
        if (pool != null) {
            pool.helpQuiesce();
        } else {
            TForkJoinPool.commonPool().awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    /** The current worker's pool, or {@code null} outside one. */
    public static @Nullable TForkJoinPool getPool() {
        return Thread.currentThread() instanceof TForkJoinWorkerThread w ? w.getPool() : null;
    }

    public static boolean inForkJoinPool() {
        return Thread.currentThread() instanceof TForkJoinWorkerThread;
    }

    /** How many tasks wait in the current worker's pool; zero outside one. */
    public static int getQueuedTaskCount() {
        TForkJoinPool pool = getPool();
        return pool != null ? pool.getQueuedSubmissionCount() : 0;
    }

    /** How many more tasks wait in the current worker's pool than it has idle workers. */
    public static int getSurplusQueuedTaskCount() {
        TForkJoinPool pool = getPool();
        return pool != null ? pool.surplusQueuedTaskCount() : 0;
    }

    protected static @Nullable TForkJoinTask<?> peekNextLocalTask() {
        TForkJoinPool pool = getPool();
        return pool != null ? pool.peekQueued() : null;
    }

    protected static @Nullable TForkJoinTask<?> pollNextLocalTask() {
        TForkJoinPool pool = getPool();
        return pool != null ? pool.pollQueued() : null;
    }

    protected static @Nullable TForkJoinTask<?> pollTask() {
        return pollNextLocalTask();
    }

    protected static @Nullable TForkJoinTask<?> pollSubmission() {
        return pollNextLocalTask();
    }

    // ---------------------------------------------------------------------------------------------
    // Tags

    public final synchronized short getForkJoinTaskTag() {
        return tag;
    }

    public final synchronized short setForkJoinTaskTag(short newValue) {
        short old = tag;
        tag = newValue;
        return old;
    }

    public final synchronized boolean compareAndSetForkJoinTaskTag(short expect, short update) {
        if (tag != expect) {
            return false;
        }
        tag = update;
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Adapters

    public static TForkJoinTask<?> adapt(Runnable runnable) {
        return new AdaptedRunnable<@Nullable Void>(runnable, null);
    }

    public static <T extends @Nullable Object> TForkJoinTask<T> adapt(Runnable runnable, T result) {
        return new AdaptedRunnable<>(runnable, result);
    }

    public static <T extends @Nullable Object> TForkJoinTask<T> adapt(Callable<? extends T> callable) {
        return new AdaptedCallable<>(callable);
    }

    /** The same as {@link #adapt(Callable)}: tasks here are never interrupted on cancel. */
    public static <T extends @Nullable Object> TForkJoinTask<T> adaptInterruptible(Callable<? extends T> callable) {
        return new AdaptedCallable<>(callable);
    }

    /** The same as {@link #adapt(Runnable, Object)}: tasks here are never interrupted on cancel. */
    public static <T extends @Nullable Object> TForkJoinTask<T> adaptInterruptible(Runnable runnable, T result) {
        return new AdaptedRunnable<>(runnable, result);
    }

    /** The same as {@link #adapt(Runnable)}: tasks here are never interrupted on cancel. */
    public static TForkJoinTask<?> adaptInterruptible(Runnable runnable) {
        return new AdaptedRunnable<@Nullable Void>(runnable, null);
    }

    /** A Runnable with a fixed result, as a task. */
    static class AdaptedRunnable<T extends @Nullable Object> extends TForkJoinTask<T> implements TRunnableFuture<T> {
        private final Runnable runnable;
        private @Nullable Object result;

        AdaptedRunnable(Runnable runnable, T result) {
            this.runnable = Objects.requireNonNull(runnable);
            this.result = result;
        }

        @Override
        public final T getRawResult() {
            return Monitors.cast(result);
        }

        @Override
        protected final void setRawResult(T value) {
            result = value;
        }

        @Override
        protected final boolean exec() {
            runnable.run();
            return true;
        }

        @Override
        public final void run() {
            invoke();
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + runnable + "]";
        }
    }

    /** A Runnable given to {@code execute}: if it throws, the worker running it ends, as in the JDK. */
    static final class RunnableExecuteAction extends AdaptedRunnable<@Nullable Void> {
        RunnableExecuteAction(Runnable runnable) {
            super(runnable, null);
        }

        @Override
        boolean failsWorker() {
            return true;
        }
    }

    /** A Callable as a task; a checked exception it throws is wrapped in a RuntimeException. */
    static final class AdaptedCallable<T extends @Nullable Object> extends TForkJoinTask<T>
            implements TRunnableFuture<T> {
        private final Callable<? extends T> callable;
        private @Nullable Object result;

        AdaptedCallable(Callable<? extends T> callable) {
            this.callable = Objects.requireNonNull(callable);
        }

        @Override
        public T getRawResult() {
            return Monitors.cast(result);
        }

        @Override
        protected void setRawResult(T value) {
            result = value;
        }

        @Override
        protected boolean exec() {
            try {
                result = callable.call();
                return true;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            invoke();
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + callable + "]";
        }
    }
}
