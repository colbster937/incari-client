package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.SnapshotIterator;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ScheduledThreadPoolExecutor}: a {@link TThreadPoolExecutor} whose
 * work queue is a delay queue, so its (core) workers take each task when it becomes due. In the
 * browser an idle worker sleeps in a timed {@code wait} until the earliest task is due; nothing
 * polls. Periodic tasks put themselves back in the queue after each run.
 */
public class TScheduledThreadPoolExecutor extends TThreadPoolExecutor implements TScheduledExecutorService {

    /** How long a thread above the core size lingers, as a real pool of this kind never has one. */
    private static final long EXTRA_THREAD_KEEP_ALIVE_MILLIS = 10L;

    /** Breaks ties between tasks due at the same time: first scheduled, first run. */
    private static final AtomicLong SEQUENCER = new AtomicLong();

    private volatile boolean continueExistingPeriodicTasksAfterShutdown;
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;
    private volatile boolean removeOnCancel;

    public TScheduledThreadPoolExecutor(int corePoolSize) {
        super(
                corePoolSize,
                Integer.MAX_VALUE,
                EXTRA_THREAD_KEEP_ALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new DelayedWorkQueue());
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, TThreadFactory threadFactory) {
        super(
                corePoolSize,
                Integer.MAX_VALUE,
                EXTRA_THREAD_KEEP_ALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new DelayedWorkQueue(),
                threadFactory);
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, TRejectedExecutionHandler handler) {
        super(
                corePoolSize,
                Integer.MAX_VALUE,
                EXTRA_THREAD_KEEP_ALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new DelayedWorkQueue(),
                handler);
    }

    public TScheduledThreadPoolExecutor(
            int corePoolSize, TThreadFactory threadFactory, TRejectedExecutionHandler handler) {
        super(
                corePoolSize,
                Integer.MAX_VALUE,
                EXTRA_THREAD_KEEP_ALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new DelayedWorkQueue(),
                threadFactory,
                handler);
    }

    // ---------------------------------------------------------------------------------------------
    // Scheduling

    /** Lets a subclass wrap or replace the task that runs {@code runnable}. Returns {@code task}. */
    protected <V extends @Nullable Object> TRunnableScheduledFuture<V> decorateTask(
            Runnable runnable, TRunnableScheduledFuture<V> task) {
        return task;
    }

    /** Lets a subclass wrap or replace the task that runs {@code callable}. Returns {@code task}. */
    protected <V extends @Nullable Object> TRunnableScheduledFuture<V> decorateTask(
            Callable<V> callable, TRunnableScheduledFuture<V> task) {
        return task;
    }

    @Override
    public TScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        ScheduledTask<@Nullable Void> task = new ScheduledTask<>(command, null, triggerTime(delay, unit), 0L);
        TRunnableScheduledFuture<@Nullable Void> decorated = decorateTask(command, task);
        delayedExecute(decorated);
        return decorated;
    }

    @Override
    public <V extends @Nullable Object> TScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        Objects.requireNonNull(callable);
        Objects.requireNonNull(unit);
        ScheduledTask<V> task = new ScheduledTask<>(callable, triggerTime(delay, unit));
        TRunnableScheduledFuture<V> decorated = decorateTask(callable, task);
        delayedExecute(decorated);
        return decorated;
    }

    @Override
    public TScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        if (period <= 0L) {
            throw new IllegalArgumentException();
        }
        return schedulePeriodic(command, triggerTime(initialDelay, unit), unit.toNanos(period));
    }

    @Override
    public TScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        if (delay <= 0L) {
            throw new IllegalArgumentException();
        }
        // A negative period marks a fixed delay, measured from the end of each run.
        return schedulePeriodic(command, triggerTime(initialDelay, unit), -unit.toNanos(delay));
    }

    private TScheduledFuture<?> schedulePeriodic(Runnable command, long firstRun, long period) {
        ScheduledTask<@Nullable Void> task = new ScheduledTask<>(command, null, firstRun, period);
        TRunnableScheduledFuture<@Nullable Void> decorated = decorateTask(command, task);
        task.requeued = decorated;
        delayedExecute(decorated);
        return decorated;
    }

    /** Runs {@code command} as soon as possible, as a zero-delay scheduled task. */
    @Override
    public void execute(Runnable command) {
        schedule(command, 0L, TimeUnit.NANOSECONDS);
    }

    @Override
    public TFuture<?> submit(Runnable task) {
        return schedule(task, 0L, TimeUnit.NANOSECONDS);
    }

    @Override
    public <T extends @Nullable Object> TFuture<T> submit(Runnable task, T result) {
        return schedule(TExecutors.callable(task, result), 0L, TimeUnit.NANOSECONDS);
    }

    @Override
    public <T extends @Nullable Object> TFuture<T> submit(Callable<T> task) {
        return schedule(task, 0L, TimeUnit.NANOSECONDS);
    }

    private static long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos(delay < 0L ? 0L : delay));
    }

    /**
     * The nanoTime at which a task delayed by {@code nanos} is due. Huge delays are capped so that
     * comparing two due times by subtraction can't overflow.
     */
    static long triggerTime(long nanos) {
        return System.nanoTime() + Math.min(nanos, Long.MAX_VALUE >> 1);
    }

    private void delayedExecute(TRunnableScheduledFuture<?> task) {
        if (isShutdown()) {
            reject(task);
            return;
        }
        getQueue().add(task);
        if (!canRunInCurrentRunState(task) && remove(task)) {
            task.cancel(false);
        } else {
            ensurePrestart();
        }
    }

    /** Puts a periodic task back in the queue after a run, if the pool still runs it. */
    void reExecutePeriodic(TRunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(task)) {
            getQueue().add(task);
            if (canRunInCurrentRunState(task) || !remove(task)) {
                ensurePrestart();
                return;
            }
        }
        task.cancel(false);
    }

    private boolean canRunInCurrentRunState(TRunnableScheduledFuture<?> task) {
        if (!isShutdown()) {
            return true;
        }
        if (isStopped()) {
            return false;
        }
        return task.isPeriodic()
                ? continueExistingPeriodicTasksAfterShutdown
                : executeExistingDelayedTasksAfterShutdown || task.getDelay(TimeUnit.NANOSECONDS) <= 0;
    }

    @Override
    void onShutdown() {
        BlockingQueue<Runnable> queue = getQueue();
        boolean keepDelayed = executeExistingDelayedTasksAfterShutdown;
        boolean keepPeriodic = continueExistingPeriodicTasksAfterShutdown;
        for (Object element : queue.toArray()) {
            if (element instanceof TRunnableScheduledFuture<?> task) {
                boolean drop = task.isCancelled() || (task.isPeriodic() ? !keepPeriodic : !keepDelayed);
                if (drop && queue.remove(task)) {
                    task.cancel(false);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Policies

    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown()) {
            onShutdown();
            tryTerminate();
        }
    }

    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown()) {
            onShutdown();
            tryTerminate();
        }
    }

    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    @Override
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    // ---------------------------------------------------------------------------------------------
    // Tasks and their queue

    /** A task with a due time; periodic ones compute the next due time after each run. */
    private final class ScheduledTask<V extends @Nullable Object> extends TFutureTask<V>
            implements TRunnableScheduledFuture<V> {

        private final long sequence = SEQUENCER.getAndIncrement();
        /** Positive: fixed rate. Negative: fixed delay. Zero: runs once. */
        private final long period;

        private volatile long time;
        /** What goes back in the queue after a periodic run: this, or its decorated form. */
        @Nullable
        TRunnableScheduledFuture<?> requeued;

        ScheduledTask(Runnable runnable, V result, long time, long period) {
            super(runnable, result);
            this.time = time;
            this.period = period;
        }

        ScheduledTask(Callable<V> callable, long time) {
            super(callable);
            this.time = time;
            this.period = 0L;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(time - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(TDelayed other) {
            if (other == this) {
                return 0;
            }
            if (other instanceof ScheduledTask<?> task) {
                long diff = time - task.time;
                if (diff != 0L) {
                    return diff < 0L ? -1 : 1;
                }
                return sequence < task.sequence ? -1 : 1;
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean isPeriodic() {
            return period != 0L;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && removeOnCancel) {
                remove(this);
            }
            return cancelled;
        }

        @Override
        public void run() {
            if (!canRunInCurrentRunState(this)) {
                cancel(false);
            } else if (!isPeriodic()) {
                super.run();
            } else if (runAndReset()) {
                time = period > 0L ? time + period : triggerTime(-period);
                TRunnableScheduledFuture<?> next = requeued;
                reExecutePeriodic(next != null ? next : this);
            }
        }
    }

    /**
     * The work queue: a {@link TDelayQueue} of scheduled tasks, seen as a queue of Runnables as
     * {@link TThreadPoolExecutor} needs. Only scheduled tasks ever go in.
     */
    private static final class DelayedWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {

        private final TDelayQueue<TRunnableScheduledFuture<?>> delegate = new TDelayQueue<>();

        private static TRunnableScheduledFuture<?> scheduled(Runnable r) {
            return (TRunnableScheduledFuture<?>) Objects.requireNonNull(r);
        }

        @Override
        public boolean offer(Runnable r) {
            return delegate.offer(scheduled(r));
        }

        @Override
        public void put(Runnable r) {
            delegate.put(scheduled(r));
        }

        @Override
        public boolean offer(Runnable r, long timeout, TimeUnit unit) {
            return delegate.offer(scheduled(r));
        }

        @Override
        public @Nullable Runnable poll() {
            return delegate.poll();
        }

        @Override
        public Runnable take() throws InterruptedException {
            return delegate.take();
        }

        @Override
        public @Nullable Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.poll(timeout, unit);
        }

        @Override
        public @Nullable Runnable peek() {
            return delegate.peek();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean remove(@Nullable Object o) {
            return delegate.remove(o);
        }

        @Override
        public boolean contains(@Nullable Object o) {
            return delegate.contains(o);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return delegate.toArray(a);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c) {
            return delegate.drainTo(c);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            return delegate.drainTo(c, maxElements);
        }

        @Override
        public Iterator<Runnable> iterator() {
            return new SnapshotIterator<>(delegate.toArray(), delegate::remove);
        }
    }
}
