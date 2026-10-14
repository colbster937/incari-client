package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ForkJoinPool}, without work stealing: all workers share one FIFO
 * queue behind the pool's monitor, and a joiner runs unstarted tasks itself (see
 * {@link TForkJoinTask}). In the browser the workers are coroutines on the page's one thread, so
 * per-worker deques would buy nothing; what matters is that nothing spins and joins can't deadlock.
 *
 * <p>Workers start on demand up to the parallelism, idle in {@code wait} and exit after the
 * keep-alive (60 s by default). {@link #managedBlock} lets one more worker start while a worker is
 * blocked, as the JDK's compensation does. {@code asyncMode} is recorded but doesn't change the
 * order (always FIFO). The common pool ignores shutdown.
 *
 * <p>Left out: the JDK 25 {@code ScheduledExecutorService} side ({@code schedule},
 * {@code submitWithTimeout}); nothing the game runs uses it.
 */
public class TForkJoinPool extends TAbstractExecutorService {

    /** Creates plain {@link TForkJoinWorkerThread}s. */
    public static final ForkJoinWorkerThreadFactory defaultForkJoinWorkerThreadFactory =
            new DefaultWorkerThreadFactory();

    private static final int MAX_PARALLELISM = 0x7fff;
    private static final long DEFAULT_KEEP_ALIVE_MILLIS = 60_000L;
    /** How many workers compensation may add beyond the parallelism by default, as in the JDK. */
    private static final int DEFAULT_EXTRA_WORKERS = 256;

    private static final int RUNNING = 0;
    private static final int SHUTDOWN = 1;
    private static final int STOP = 2;
    private static final int TERMINATED = 3;

    private static final AtomicInteger POOL_IDS = new AtomicInteger(1);

    /** Guards the queue and counts below; only idle workers wait on it. */
    private final Object lock = new Object();
    /** Termination and quiescence waiters wait on this one, so a worker wake-up never hits them. */
    private final Object stateMonitor = new Object();

    private final ForkJoinWorkerThreadFactory factory;
    private final Thread.@Nullable UncaughtExceptionHandler handler;
    private final boolean asyncMode;
    private final boolean common;
    private final String workerNamePrefix;
    private final long keepAliveMillis;
    private final int maximumPoolSize;
    private final AtomicInteger workerIndices = new AtomicInteger();
    private volatile int parallelism;

    // Guarded by lock.
    private final ArrayDeque<TForkJoinTask<?>> queue = new ArrayDeque<>();
    private final HashSet<TForkJoinWorkerThread> workers = new HashSet<>();
    private int workerCount; // includes workers being created
    // Threads registered and not yet through deregisterWorker, so their onTermination hooks have run.
    private int unfinishedWorkers;
    private int idleWorkers;
    private int activeTasks;
    private int compensation;
    private long completedTasks;
    private int runState = RUNNING;

    public TForkJoinPool() {
        this(Math.min(MAX_PARALLELISM, Runtime.getRuntime().availableProcessors()));
    }

    public TForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    public TForkJoinPool(
            int parallelism,
            ForkJoinWorkerThreadFactory factory,
            Thread.@Nullable UncaughtExceptionHandler handler,
            boolean asyncMode) {
        this(
                parallelism,
                factory,
                handler,
                asyncMode,
                false,
                parallelism + DEFAULT_EXTRA_WORKERS,
                DEFAULT_KEEP_ALIVE_MILLIS);
    }

    /**
     * The JDK's full constructor. {@code corePoolSize}, {@code minimumRunnable} and
     * {@code saturate} are accepted and ignored: workers here always start on demand.
     */
    public TForkJoinPool(
            int parallelism,
            ForkJoinWorkerThreadFactory factory,
            Thread.@Nullable UncaughtExceptionHandler handler,
            boolean asyncMode,
            int corePoolSize,
            int maximumPoolSize,
            int minimumRunnable,
            @Nullable Predicate<? super TForkJoinPool> saturate,
            long keepAliveTime,
            TimeUnit unit) {
        this(
                parallelism,
                factory,
                handler,
                asyncMode,
                false,
                checkMaximum(parallelism, maximumPoolSize),
                Math.max(1L, unit.toMillis(checkKeepAlive(keepAliveTime))));
    }

    private TForkJoinPool(
            int parallelism,
            ForkJoinWorkerThreadFactory factory,
            Thread.@Nullable UncaughtExceptionHandler handler,
            boolean asyncMode,
            boolean common,
            int maximumPoolSize,
            long keepAliveMillis) {
        if (parallelism <= 0 || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException();
        }
        this.parallelism = parallelism;
        this.factory = Objects.requireNonNull(factory);
        this.handler = handler;
        this.asyncMode = asyncMode;
        this.common = common;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveMillis = keepAliveMillis;
        this.workerNamePrefix =
                common ? "ForkJoinPool.commonPool-worker-" : "ForkJoinPool-" + POOL_IDS.getAndIncrement() + "-worker-";
    }

    private static int checkMaximum(int parallelism, int maximumPoolSize) {
        if (maximumPoolSize < parallelism) {
            throw new IllegalArgumentException();
        }
        return maximumPoolSize;
    }

    private static long checkKeepAlive(long keepAliveTime) {
        if (keepAliveTime <= 0L) {
            throw new IllegalArgumentException();
        }
        return keepAliveTime;
    }

    /** Holds the common pool, so it's only made when first used. */
    private static final class Common {
        static final TForkJoinPool POOL = new TForkJoinPool(
                Math.max(1, Math.min(MAX_PARALLELISM, Runtime.getRuntime().availableProcessors() - 1)),
                defaultForkJoinWorkerThreadFactory,
                null,
                false,
                true,
                MAX_PARALLELISM,
                DEFAULT_KEEP_ALIVE_MILLIS);
    }

    /** The shared pool that {@link TForkJoinTask#fork} uses outside a pool. Never shuts down. */
    public static TForkJoinPool commonPool() {
        return Common.POOL;
    }

    public static int getCommonPoolParallelism() {
        return commonPool().getParallelism();
    }

    // ---------------------------------------------------------------------------------------------
    // Workers

    final int nextWorkerIndex() {
        return workerIndices.getAndIncrement();
    }

    final String workerName(int index) {
        return workerNamePrefix + (index + 1);
    }

    /** Queues {@code task} and makes sure a worker will look at the queue. */
    final void push(TForkJoinTask<?> task) {
        boolean spawn = false;
        synchronized (lock) {
            queue.addLast(task);
            if (idleWorkers > 0) {
                lock.notify();
            }
            if (runState == RUNNING
                    && queue.size() > idleWorkers
                    && workerCount < Math.min(maximumPoolSize, parallelism + compensation)) {
                workerCount++;
                spawn = true;
            }
        }
        if (spawn) {
            spawnWorker();
        }
    }

    private void externalPush(TForkJoinTask<?> task) {
        if (isShutdown()) {
            throw new TRejectedExecutionException("The pool has been shut down");
        }
        push(task);
    }

    // The caller has already counted the worker in workerCount.
    private void spawnWorker() {
        TForkJoinWorkerThread worker = null;
        try {
            worker = factory.newThread(this);
        } finally {
            if (worker == null) {
                synchronized (lock) {
                    workerCount--;
                }
                tryTerminate();
            }
        }
        if (worker == null) {
            return;
        }
        if (handler != null) {
            worker.setUncaughtExceptionHandler(handler);
        }
        synchronized (lock) {
            workers.add(worker);
            unfinishedWorkers++;
        }
        try {
            worker.start();
        } catch (RuntimeException e) {
            deregisterWorker(worker, null);
            throw e;
        }
    }

    /** The worker loop, called by {@link TForkJoinWorkerThread#run}; returns when it should exit. */
    final void runWorker(TForkJoinWorkerThread worker) {
        while (true) {
            TForkJoinTask<?> task;
            synchronized (lock) {
                task = nextTaskOrExit(worker);
                if (task != null) {
                    activeTasks++;
                }
            }
            if (task == null) {
                tryTerminate();
                signalState();
                return;
            }
            try {
                Throwable failure = task.runClaimed();
                if (failure != null && task.failsWorker()) {
                    throw Monitors.rethrow(failure);
                }
            } finally {
                boolean quiet;
                synchronized (lock) {
                    activeTasks--;
                    completedTasks++;
                    quiet = activeTasks == 0 && queue.isEmpty();
                }
                if (quiet) {
                    signalState();
                }
            }
        }
    }

    /**
     * Caller holds lock. A task the worker has claimed, or {@code null} once it should exit, in which
     * case it has already stopped being counted, so a concurrent push starts a replacement.
     */
    private @Nullable TForkJoinTask<?> nextTaskOrExit(TForkJoinWorkerThread worker) {
        long idleSince = -1L;
        while (true) {
            if (runState >= STOP) {
                removeWorker(worker);
                return null;
            }
            TForkJoinTask<?> task = claimNext();
            if (task != null) {
                return task;
            }
            if (runState != RUNNING || workerCount > Math.min(maximumPoolSize, parallelism + compensation)) {
                removeWorker(worker);
                return null;
            }
            long now = System.currentTimeMillis();
            if (idleSince < 0L) {
                idleSince = now;
            } else if (now - idleSince >= keepAliveMillis) {
                removeWorker(worker);
                return null;
            }
            idleWorkers++;
            try {
                lock.wait(keepAliveMillis);
            } catch (InterruptedException e) {
                // shutdownNow, or a stray interrupt: look at the state again.
            } finally {
                idleWorkers--;
            }
        }
    }

    // Caller holds lock. Skips tasks that a joiner claimed after they were queued.
    private @Nullable TForkJoinTask<?> claimNext() {
        TForkJoinTask<?> task;
        while ((task = queue.pollFirst()) != null) {
            if (task.tryClaim()) {
                return task;
            }
        }
        return null;
    }

    // Caller holds lock.
    private void removeWorker(TForkJoinWorkerThread worker) {
        if (!worker.deregistered) {
            worker.deregistered = true;
            workerCount--;
        }
        workers.remove(worker);
    }

    /** Called as a worker ends, normally or not; replaces a worker that a failing task ended. */
    final void deregisterWorker(TForkJoinWorkerThread worker, @Nullable Throwable exception) {
        boolean replace = false;
        synchronized (lock) {
            removeWorker(worker);
            unfinishedWorkers--;
            if (exception != null && runState == RUNNING && !queue.isEmpty() && idleWorkers == 0) {
                workerCount++;
                replace = true;
            }
        }
        if (replace) {
            spawnWorker();
        }
        tryTerminate();
        signalState();
    }

    private void tryTerminate() {
        boolean terminated = false;
        synchronized (lock) {
            // A worker leaves workerCount before its onTermination hook runs; terminate only after it has.
            if (workerCount == 0
                    && unfinishedWorkers == 0
                    && (runState == STOP || (runState == SHUTDOWN && queue.isEmpty() && activeTasks == 0))) {
                runState = TERMINATED;
                terminated = true;
            }
        }
        if (terminated) {
            signalState();
        }
    }

    private void signalState() {
        synchronized (stateMonitor) {
            stateMonitor.notifyAll();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Submitting work

    /** Runs {@code task} to completion and returns its result, rethrowing what it threw. */
    public <T extends @Nullable Object> T invoke(TForkJoinTask<T> task) {
        Objects.requireNonNull(task);
        if (isOwnWorker()) {
            return task.invoke();
        }
        externalPush(task);
        boolean noWorkers;
        synchronized (lock) {
            noWorkers = workerCount == 0;
        }
        if (noWorkers) {
            // The factory refused to make a worker, so nobody else will run it.
            return task.join();
        }
        task.awaitWithoutRunning();
        return task.reportJoin();
    }

    public void execute(TForkJoinTask<?> task) {
        externalPush(Objects.requireNonNull(task));
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task);
        externalPush(
                task instanceof TForkJoinTask<?> forkJoinTask
                        ? forkJoinTask
                        : new TForkJoinTask.RunnableExecuteAction(task));
    }

    public <T extends @Nullable Object> TForkJoinTask<T> submit(TForkJoinTask<T> task) {
        externalPush(Objects.requireNonNull(task));
        return task;
    }

    @Override
    public <T extends @Nullable Object> TForkJoinTask<T> submit(Callable<T> task) {
        TForkJoinTask<T> adapted = new TForkJoinTask.AdaptedCallable<>(task);
        externalPush(adapted);
        return adapted;
    }

    @Override
    public <T extends @Nullable Object> TForkJoinTask<T> submit(Runnable task, T result) {
        TForkJoinTask<T> adapted = new TForkJoinTask.AdaptedRunnable<>(task, result);
        externalPush(adapted);
        return adapted;
    }

    @Override
    public TForkJoinTask<?> submit(Runnable task) {
        Objects.requireNonNull(task);
        TForkJoinTask<?> adapted = task instanceof TForkJoinTask<?> forkJoinTask
                ? forkJoinTask
                : new TForkJoinTask.AdaptedRunnable<@Nullable Void>(task, null);
        externalPush(adapted);
        return adapted;
    }

    public <T extends @Nullable Object> TForkJoinTask<T> externalSubmit(TForkJoinTask<T> task) {
        return submit(task);
    }

    public <T extends @Nullable Object> TForkJoinTask<T> lazySubmit(TForkJoinTask<T> task) {
        return submit(task);
    }

    @Override
    protected <T extends @Nullable Object> TRunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new TForkJoinTask.AdaptedRunnable<>(runnable, value);
    }

    @Override
    protected <T extends @Nullable Object> TRunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new TForkJoinTask.AdaptedCallable<>(callable);
    }

    @Override
    public <T extends @Nullable Object> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return invokeAllUninterruptibly(tasks);
    }

    /** Runs every task and returns their futures once all are done. */
    public <T extends @Nullable Object> List<TFuture<T>> invokeAllUninterruptibly(
            Collection<? extends Callable<T>> tasks) {
        List<TForkJoinTask<T>> adapted = new ArrayList<>(tasks.size());
        boolean finished = false;
        try {
            for (Callable<T> task : tasks) {
                TForkJoinTask<T> f = new TForkJoinTask.AdaptedCallable<>(task);
                adapted.add(f);
                externalPush(f);
            }
            for (TForkJoinTask<T> f : adapted) {
                f.quietlyJoin();
            }
            finished = true;
            return new ArrayList<>(adapted);
        } finally {
            if (!finished) {
                for (TForkJoinTask<T> f : adapted) {
                    f.cancel(false);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Queue access for TForkJoinTask

    /** Whether the current thread is one of this pool's workers. */
    @SuppressWarnings("ReferenceEquality") // pools are told apart by identity
    private boolean isOwnWorker() {
        return TForkJoinTask.getPool() == this;
    }

    @SuppressWarnings("ReferenceEquality") // it's this very task that must come out of the queue
    final boolean tryRemove(TForkJoinTask<?> task) {
        synchronized (lock) {
            for (Iterator<TForkJoinTask<?>> it = queue.iterator(); it.hasNext(); ) {
                if (it.next() == task) {
                    it.remove();
                    return task.isUnclaimed();
                }
            }
            return false;
        }
    }

    final @Nullable TForkJoinTask<?> peekQueued() {
        synchronized (lock) {
            for (TForkJoinTask<?> task : queue) {
                if (task.isUnclaimed()) {
                    return task;
                }
            }
            return null;
        }
    }

    // Unclaimed, so the caller can run it with invoke or exec.
    final @Nullable TForkJoinTask<?> pollQueued() {
        synchronized (lock) {
            TForkJoinTask<?> task;
            while ((task = queue.pollFirst()) != null) {
                if (task.isUnclaimed()) {
                    return task;
                }
            }
            return null;
        }
    }

    final int surplusQueuedTaskCount() {
        synchronized (lock) {
            return Math.max(0, queue.size() - idleWorkers);
        }
    }

    /** Runs this pool's queued tasks on the calling worker until the queue is empty. */
    final void helpQuiesce() {
        while (true) {
            TForkJoinTask<?> task;
            synchronized (lock) {
                task = claimNext();
            }
            if (task == null) {
                return;
            }
            Throwable failure = task.runClaimed();
            if (failure != null && task.failsWorker()) {
                throw Monitors.rethrow(failure);
            }
        }
    }

    protected @Nullable TForkJoinTask<?> pollSubmission() {
        return pollQueued();
    }

    protected int drainTasksTo(Collection<? super TForkJoinTask<?>> c) {
        int n = 0;
        TForkJoinTask<?> task;
        while ((task = pollQueued()) != null) {
            c.add(task);
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------
    // Blocking

    /** {@code java.util.concurrent.ForkJoinPool.ManagedBlocker}. */
    public interface ManagedBlocker {
        /** Blocks if needed; returns true once no more blocking is necessary. */
        boolean block() throws InterruptedException;

        /** Whether blocking is unnecessary. */
        boolean isReleasable();
    }

    /**
     * Blocks with {@code blocker} until it's releasable. On a worker, lets the pool start one extra
     * worker meanwhile if tasks are waiting, so blocked workers can't starve the queue.
     */
    public static void managedBlock(ManagedBlocker blocker) throws InterruptedException {
        Objects.requireNonNull(blocker);
        if (blocker.isReleasable()) {
            return;
        }
        TForkJoinPool pool = TForkJoinTask.getPool();
        if (pool != null) {
            pool.beginCompensation();
        }
        try {
            while (!blocker.isReleasable() && !blocker.block()) {
                // block() returned false: it wants to be called again.
            }
        } finally {
            if (pool != null) {
                pool.endCompensation();
            }
        }
    }

    private void beginCompensation() {
        boolean spawn = false;
        synchronized (lock) {
            compensation++;
            if (runState == RUNNING
                    && !queue.isEmpty()
                    && idleWorkers == 0
                    && workerCount < Math.min(maximumPoolSize, parallelism + compensation)) {
                workerCount++;
                spawn = true;
            }
        }
        if (spawn) {
            spawnWorker();
        }
    }

    private void endCompensation() {
        synchronized (lock) {
            compensation--;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Queries

    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    public Thread.@Nullable UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return handler;
    }

    public int getParallelism() {
        return parallelism;
    }

    /** Sets the target parallelism and returns the previous one. */
    public int setParallelism(int size) {
        if (size <= 0 || size > MAX_PARALLELISM) {
            throw new IllegalArgumentException();
        }
        int old = parallelism;
        parallelism = size;
        return old;
    }

    public int getPoolSize() {
        synchronized (lock) {
            return workerCount;
        }
    }

    public boolean getAsyncMode() {
        return asyncMode;
    }

    public int getRunningThreadCount() {
        return getActiveThreadCount();
    }

    public int getActiveThreadCount() {
        synchronized (lock) {
            return activeTasks;
        }
    }

    public boolean isQuiescent() {
        synchronized (lock) {
            return activeTasks == 0 && queue.isEmpty();
        }
    }

    /** The number of tasks workers have run (there's no stealing to count). */
    public long getStealCount() {
        synchronized (lock) {
            return completedTasks;
        }
    }

    /** Always zero: there are no per-worker queues; everything queued counts as a submission. */
    public long getQueuedTaskCount() {
        return 0L;
    }

    public int getQueuedSubmissionCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Always zero: this pool has no delayed tasks. */
    public long getDelayedTaskCount() {
        return 0L;
    }

    public boolean hasQueuedSubmissions() {
        synchronized (lock) {
            return !queue.isEmpty();
        }
    }

    @Override
    public String toString() {
        String state;
        int size;
        int active;
        int queued;
        long completed;
        synchronized (lock) {
            state = switch (runState) {
                case RUNNING -> "Running";
                case TERMINATED -> "Terminated";
                default -> "Shutting down";
            };
            size = workerCount;
            active = activeTasks;
            queued = queue.size();
            completed = completedTasks;
        }
        return super.toString()
                + "[" + state
                + ", parallelism = " + parallelism
                + ", size = " + size
                + ", active = " + active
                + ", running = " + active
                + ", steals = " + completed
                + ", tasks = 0"
                + ", submissions = " + queued
                + "]";
    }

    // ---------------------------------------------------------------------------------------------
    // Shutdown

    /** Stops accepting tasks; queued ones still run. No effect on the common pool. */
    @Override
    public void shutdown() {
        if (common) {
            return;
        }
        boolean spawn = false;
        synchronized (lock) {
            if (runState == RUNNING) {
                runState = SHUTDOWN;
            }
            // Idle workers wake, find the queue empty and exit.
            lock.notifyAll();
            if (runState == SHUTDOWN && !queue.isEmpty() && workerCount == 0) {
                workerCount++;
                spawn = true;
            }
        }
        if (spawn) {
            spawnWorker();
        }
        tryTerminate();
    }

    /**
     * Cancels the queued tasks and interrupts the workers. Returns an empty list, as the JDK's does.
     * No effect on the common pool.
     */
    @Override
    public List<Runnable> shutdownNow() {
        if (common) {
            return new ArrayList<>();
        }
        List<TForkJoinTask<?>> dropped;
        List<Thread> running;
        synchronized (lock) {
            if (runState < STOP) {
                runState = STOP;
            }
            dropped = new ArrayList<>(queue);
            queue.clear();
            running = new ArrayList<>(workers);
            lock.notifyAll();
        }
        for (TForkJoinTask<?> task : dropped) {
            task.cancel(false);
        }
        for (Thread t : running) {
            t.interrupt();
        }
        tryTerminate();
        return new ArrayList<>();
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return runState >= SHUTDOWN;
        }
    }

    public boolean isTerminating() {
        synchronized (lock) {
            return runState >= SHUTDOWN && runState < TERMINATED;
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return runState == TERMINATED;
        }
    }

    /** Waits for termination; for the common pool, which never terminates, waits for quiescence. */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Monitors.checkInterrupt();
        if (common) {
            awaitQuiescence(timeout, unit);
            return false;
        }
        long nanos = unit.toNanos(timeout);
        synchronized (stateMonitor) {
            while (!isTerminated()) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = Monitors.awaitNanos(stateMonitor, nanos);
            }
            return true;
        }
    }

    /**
     * Waits until no task is queued or running. On one of this pool's workers it runs the queued
     * tasks instead ({@link TForkJoinTask#helpQuiesce}). An interrupt ends the wait early (returning
     * false, with the flag re-asserted).
     */
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        if (isOwnWorker()) {
            helpQuiesce();
            return true;
        }
        long nanos = unit.toNanos(timeout);
        synchronized (stateMonitor) {
            while (!isQuiescent()) {
                if (nanos <= 0) {
                    return false;
                }
                try {
                    nanos = Monitors.awaitNanos(stateMonitor, nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Shuts down and waits for termination; no effect on the common pool. */
    @Override
    public void close() {
        if (common) {
            return;
        }
        shutdown();
        boolean interrupted = false;
        while (!isTerminated()) {
            try {
                awaitTermination(1L, TimeUnit.DAYS);
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

    // ---------------------------------------------------------------------------------------------
    // Worker factories

    /** {@code java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory}. */
    public interface ForkJoinWorkerThreadFactory {
        /** A new, unstarted worker for {@code pool}, or {@code null} to refuse. */
        @Nullable
        TForkJoinWorkerThread newThread(TForkJoinPool pool);
    }

    private static final class DefaultWorkerThreadFactory implements ForkJoinWorkerThreadFactory {
        @Override
        public TForkJoinWorkerThread newThread(TForkJoinPool pool) {
            return new TForkJoinWorkerThread(pool);
        }
    }
}
