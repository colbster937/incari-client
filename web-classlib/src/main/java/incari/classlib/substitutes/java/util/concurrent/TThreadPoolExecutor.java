package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ThreadPoolExecutor}: core and maximum pool sizes, keep-alive, a
 * caller-supplied work queue, rejection handlers and the {@code beforeExecute}/{@code afterExecute}/
 * {@code terminated} hooks, following the JDK's documented policy (start a core thread, else queue,
 * else start a non-core thread, else reject).
 *
 * <p>Pool state sits behind one private monitor. In the browser the workers are coroutines that
 * suspend inside the work queue's {@code take}/{@code poll}; shutting down interrupts the idle ones
 * so they wake and exit. Workers are never spun: an idle worker is always blocked in the queue.
 */
public class TThreadPoolExecutor extends TAbstractExecutorService {

    private static final int RUNNING = 0;
    private static final int SHUTDOWN = 1;
    private static final int STOP = 2;
    private static final int TIDYING = 3;
    private static final int TERMINATED = 4;

    private static final TRejectedExecutionHandler DEFAULT_HANDLER = new AbortPolicy();

    private final Object lock = new Object();
    private final BlockingQueue<Runnable> workQueue;

    // Guarded by lock.
    private final HashSet<Worker> workers = new HashSet<>();
    private int runState = RUNNING;
    private int largestPoolSize;
    private long completedTaskCount;

    private volatile TThreadFactory threadFactory;
    private volatile TRejectedExecutionHandler handler;
    private volatile int corePoolSize;
    private volatile int maximumPoolSize;
    private volatile long keepAliveNanos;
    private volatile boolean allowCoreThreadTimeOut;

    public TThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        this(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                unit,
                workQueue,
                TExecutors.defaultThreadFactory(),
                DEFAULT_HANDLER);
    }

    public TThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            TThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, DEFAULT_HANDLER);
    }

    public TThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            TRejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, TExecutors.defaultThreadFactory(), handler);
    }

    public TThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            TThreadFactory threadFactory,
            TRejectedExecutionHandler handler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) {
            throw new IllegalArgumentException();
        }
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveNanos = unit.toNanos(keepAliveTime);
        this.workQueue = Objects.requireNonNull(workQueue);
        this.threadFactory = Objects.requireNonNull(threadFactory);
        this.handler = Objects.requireNonNull(handler);
    }

    // ---------------------------------------------------------------------------------------------
    // Submitting work

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command);
        if (startWorker(command, true)) {
            return;
        }
        if (!isShutdown() && workQueue.offer(command)) {
            boolean rejectIt = false;
            synchronized (lock) {
                if (runState != RUNNING && workQueue.remove(command)) {
                    rejectIt = true;
                }
            }
            if (rejectIt) {
                reject(command);
            } else {
                ensureWorkerForQueue();
            }
            return;
        }
        if (!startWorker(command, false)) {
            reject(command);
        }
    }

    /**
     * For {@link TScheduledThreadPoolExecutor}, which queues directly: starts a core thread if the
     * pool has fewer than its core size, or one thread if it has none at all.
     */
    final void ensurePrestart() {
        if (!startWorker(null, true)) {
            ensureWorkerForQueue();
        }
    }

    /** Makes sure some worker will drain the queue, as a pool with no core threads may have none. */
    private void ensureWorkerForQueue() {
        boolean none;
        synchronized (lock) {
            none = workers.isEmpty();
        }
        if (none) {
            startWorker(null, false);
        }
    }

    /**
     * Starts a worker running {@code firstTask} (then the queue) if the pool is below its core
     * ({@code core}) or maximum size and still accepts work. The thread factory is called under the
     * pool monitor so two callers can't both pass the size check.
     */
    private boolean startWorker(@Nullable Runnable firstTask, boolean core) {
        Worker worker;
        Thread thread;
        synchronized (lock) {
            if (runState >= STOP || (runState == SHUTDOWN && (firstTask != null || workQueue.isEmpty()))) {
                return false;
            }
            if (workers.size() >= (core ? corePoolSize : maximumPoolSize)) {
                return false;
            }
            worker = new Worker(firstTask);
            Thread created = threadFactory.newThread(worker);
            if (created == null) {
                return false;
            }
            thread = created;
            worker.thread = thread;
            workers.add(worker);
            largestPoolSize = Math.max(largestPoolSize, workers.size());
        }
        try {
            thread.start();
        } catch (RuntimeException e) {
            synchronized (lock) {
                workers.remove(worker);
            }
            tryTerminate();
            throw e;
        }
        return true;
    }

    /** Hands {@code command} to the rejection handler. */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    // ---------------------------------------------------------------------------------------------
    // Workers

    /** One pool thread's state; guarded by the pool's lock except {@code firstTask}. */
    private final class Worker implements Runnable {
        @Nullable
        Runnable firstTask;

        @Nullable
        Thread thread;

        boolean running;
        long completedTasks;

        Worker(@Nullable Runnable firstTask) {
            this.firstTask = firstTask;
        }

        @Override
        public void run() {
            runWorker(this);
        }
    }

    private void runWorker(Worker w) {
        Thread current = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        boolean abrupt = true;
        try {
            while (task != null || (task = getTask(w)) != null) {
                boolean stopping;
                synchronized (lock) {
                    w.running = true;
                    stopping = runState >= STOP;
                }
                // Clear an interrupt meant for an idle worker (or left by a cancelled task) so
                // it doesn't leak into this task, but keep the one that says the pool is stopping.
                if (stopping) {
                    current.interrupt();
                } else {
                    Thread.interrupted();
                }
                try {
                    beforeExecute(current, task);
                    try {
                        task.run();
                    } catch (Throwable t) {
                        afterExecute(task, t);
                        throw t;
                    }
                    afterExecute(task, null);
                } finally {
                    task = null;
                    synchronized (lock) {
                        w.completedTasks++;
                        w.running = false;
                    }
                }
            }
            abrupt = false;
        } finally {
            workerExited(w, abrupt);
        }
    }

    /**
     * The next task for {@code w}, blocking in the queue while idle, or {@code null} when the
     * worker should exit, in which case it has already been removed from the pool.
     */
    private @Nullable Runnable getTask(Worker w) {
        boolean timedOut = false;
        while (true) {
            boolean timed;
            synchronized (lock) {
                if (runState >= STOP || (runState >= SHUTDOWN && workQueue.isEmpty())) {
                    removeWorker(w);
                    return null;
                }
                int count = workers.size();
                timed = allowCoreThreadTimeOut || count > corePoolSize;
                if ((count > maximumPoolSize || (timed && timedOut)) && (count > 1 || workQueue.isEmpty())) {
                    removeWorker(w);
                    return null;
                }
            }
            try {
                Runnable r = timed ? workQueue.poll(keepAliveNanos, TimeUnit.NANOSECONDS) : workQueue.take();
                if (r != null) {
                    return r;
                }
                timedOut = true;
            } catch (InterruptedException e) {
                // Woken by shutdown or a resize: loop and look at the state again.
                timedOut = false;
            }
        }
    }

    // Caller holds lock.
    private void removeWorker(Worker w) {
        if (workers.remove(w)) {
            completedTaskCount += w.completedTasks;
        }
    }

    private void workerExited(Worker w, boolean abrupt) {
        boolean replace = false;
        synchronized (lock) {
            removeWorker(w);
            if (runState < STOP) {
                if (abrupt) {
                    replace = true;
                } else {
                    int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                    if (min == 0 && !workQueue.isEmpty()) {
                        min = 1;
                    }
                    replace = workers.size() < min;
                }
            }
        }
        tryTerminate();
        if (replace) {
            startWorker(null, false);
        }
    }

    // Caller holds lock.
    private void interruptIdleWorkers(boolean onlyOne) {
        for (Worker w : workers) {
            Thread t = w.thread;
            if (!w.running && t != null && !t.equals(Thread.currentThread())) {
                t.interrupt();
                if (onlyOne) {
                    return;
                }
            }
        }
    }

    /**
     * Moves to TERMINATED once the pool is shut down, has no workers and nothing queued. While
     * workers remain it wakes one idle worker, which exits and calls this again: that chain is how
     * a shutdown reaches workers that went back to the queue after {@link #shutdown} interrupted.
     */
    final void tryTerminate() {
        synchronized (lock) {
            if (runState == RUNNING || runState >= TIDYING || (runState == SHUTDOWN && !workQueue.isEmpty())) {
                return;
            }
            if (!workers.isEmpty()) {
                interruptIdleWorkers(true);
                return;
            }
            runState = TIDYING;
        }
        try {
            terminated();
        } finally {
            synchronized (lock) {
                runState = TERMINATED;
                lock.notifyAll();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shutdown

    @Override
    public void shutdown() {
        synchronized (lock) {
            if (runState < SHUTDOWN) {
                runState = SHUTDOWN;
            }
            interruptIdleWorkers(false);
        }
        onShutdown();
        tryTerminate();
    }

    /** Hook for {@link TScheduledThreadPoolExecutor}: drop the tasks its policies don't keep. */
    void onShutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> pending = new ArrayList<>();
        synchronized (lock) {
            if (runState < STOP) {
                runState = STOP;
            }
            for (Worker w : workers) {
                Thread t = w.thread;
                if (t != null) {
                    t.interrupt();
                }
            }
            workQueue.drainTo(pending);
            // A delay queue only drains what's due: take the rest one by one.
            if (!workQueue.isEmpty()) {
                for (Object r : workQueue.toArray()) {
                    if (workQueue.remove(r)) {
                        pending.add((Runnable) r);
                    }
                }
            }
        }
        tryTerminate();
        return pending;
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return runState >= SHUTDOWN;
        }
    }

    /** Whether the pool has shut down past the point where it stops running tasks. */
    final boolean isStopped() {
        synchronized (lock) {
            return runState >= STOP;
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

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (lock) {
            Monitors.checkInterrupt();
            while (runState != TERMINATED) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = Monitors.awaitNanos(lock, nanos);
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration

    public void setThreadFactory(TThreadFactory threadFactory) {
        this.threadFactory = Objects.requireNonNull(threadFactory);
    }

    public TThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public void setRejectedExecutionHandler(TRejectedExecutionHandler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    public TRejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0 || maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException();
        }
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        int toStart = 0;
        synchronized (lock) {
            if (workers.size() > corePoolSize) {
                interruptIdleWorkers(false);
            } else if (delta > 0) {
                toStart = Math.min(delta, workQueue.size());
            }
        }
        while (toStart-- > 0 && startWorker(null, true)) {
            if (workQueue.isEmpty()) {
                break;
            }
        }
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public boolean prestartCoreThread() {
        return startWorker(null, true);
    }

    public int prestartAllCoreThreads() {
        int started = 0;
        while (startWorker(null, true)) {
            started++;
        }
        return started;
    }

    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveNanos <= 0) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value) {
                synchronized (lock) {
                    interruptIdleWorkers(false);
                }
            }
        }
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException();
        }
        this.maximumPoolSize = maximumPoolSize;
        synchronized (lock) {
            if (workers.size() > maximumPoolSize) {
                interruptIdleWorkers(false);
            }
        }
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0) {
            throw new IllegalArgumentException();
        }
        if (time == 0 && allowsCoreThreadTimeOut()) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        long nanos = unit.toNanos(time);
        long delta = nanos - keepAliveNanos;
        keepAliveNanos = nanos;
        if (delta < 0) {
            synchronized (lock) {
                interruptIdleWorkers(false);
            }
        }
    }

    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveNanos, TimeUnit.NANOSECONDS);
    }

    // ---------------------------------------------------------------------------------------------
    // Queue maintenance and statistics

    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate();
        return removed;
    }

    /** Removes cancelled futures from the queue. */
    public void purge() {
        for (Object r : workQueue.toArray()) {
            if (r instanceof TFuture<?> future && future.isCancelled()) {
                workQueue.remove(r);
            }
        }
        tryTerminate();
    }

    public int getPoolSize() {
        synchronized (lock) {
            return runState >= TIDYING ? 0 : workers.size();
        }
    }

    public int getActiveCount() {
        synchronized (lock) {
            int active = 0;
            for (Worker w : workers) {
                if (w.running) {
                    active++;
                }
            }
            return active;
        }
    }

    public int getLargestPoolSize() {
        synchronized (lock) {
            return largestPoolSize;
        }
    }

    public long getTaskCount() {
        synchronized (lock) {
            long count = completedTaskCount;
            for (Worker w : workers) {
                count += w.completedTasks;
                if (w.running) {
                    count++;
                }
            }
            return count + workQueue.size();
        }
    }

    public long getCompletedTaskCount() {
        synchronized (lock) {
            long count = completedTaskCount;
            for (Worker w : workers) {
                count += w.completedTasks;
            }
            return count;
        }
    }

    @Override
    public String toString() {
        String state;
        int poolSize;
        synchronized (lock) {
            state = runState == RUNNING ? "Running" : runState == TERMINATED ? "Terminated" : "Shutting down";
            poolSize = workers.size();
        }
        return super.toString()
                + "[" + state
                + ", pool size = " + poolSize
                + ", active threads = " + getActiveCount()
                + ", queued tasks = " + workQueue.size()
                + ", completed tasks = " + getCompletedTaskCount()
                + "]";
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks

    /** Runs on the worker thread before each task. Does nothing by default. */
    protected void beforeExecute(Thread t, Runnable r) {}

    /** Runs on the worker thread after each task, with what it threw, if anything. */
    protected void afterExecute(Runnable r, @Nullable Throwable t) {}

    /** Runs once the pool has terminated. Does nothing by default. */
    protected void terminated() {}

    // ---------------------------------------------------------------------------------------------
    // Rejection policies

    /** Throws {@link TRejectedExecutionException}; the default. */
    public static class AbortPolicy implements TRejectedExecutionHandler {
        public AbortPolicy() {}

        @Override
        public void rejectedExecution(Runnable r, TThreadPoolExecutor e) {
            throw new TRejectedExecutionException("Task " + r + " rejected from " + e);
        }
    }

    /** Runs the task on the submitting thread, unless the pool has shut down. */
    public static class CallerRunsPolicy implements TRejectedExecutionHandler {
        public CallerRunsPolicy() {}

        @Override
        public void rejectedExecution(Runnable r, TThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /** Drops the task. */
    public static class DiscardPolicy implements TRejectedExecutionHandler {
        public DiscardPolicy() {}

        @Override
        public void rejectedExecution(Runnable r, TThreadPoolExecutor e) {}
    }

    /** Drops the oldest queued task and tries again, unless the pool has shut down. */
    public static class DiscardOldestPolicy implements TRejectedExecutionHandler {
        public DiscardOldestPolicy() {}

        @Override
        public void rejectedExecution(Runnable r, TThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
