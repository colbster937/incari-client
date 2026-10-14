package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.Executors}. The pools are the JDK's configurations of
 * {@link TThreadPoolExecutor}, {@link TScheduledThreadPoolExecutor} and {@link TForkJoinPool}.
 *
 * <p>The browser has no virtual threads: {@link #newVirtualThreadPerTaskExecutor} starts an
 * ordinary (daemon) thread per task, which is a coroutine there anyway. There are no thread groups
 * or security managers either, so the "privileged" variants behave like the plain ones.
 */
public final class TExecutors {

    private TExecutors() {}

    public static TExecutorService newFixedThreadPool(int nThreads) {
        return new TThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new TLinkedBlockingQueue<>());
    }

    public static TExecutorService newFixedThreadPool(int nThreads, TThreadFactory threadFactory) {
        return new TThreadPoolExecutor(
                nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new TLinkedBlockingQueue<>(), threadFactory);
    }

    public static TExecutorService newWorkStealingPool(int parallelism) {
        return new TForkJoinPool(parallelism, TForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
    }

    public static TExecutorService newWorkStealingPool() {
        return newWorkStealingPool(Runtime.getRuntime().availableProcessors());
    }

    public static TExecutorService newSingleThreadExecutor() {
        return new DelegatedExecutorService(
                new TThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new TLinkedBlockingQueue<>()));
    }

    public static TExecutorService newSingleThreadExecutor(TThreadFactory threadFactory) {
        return new DelegatedExecutorService(
                new TThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new TLinkedBlockingQueue<>(), threadFactory));
    }

    public static TExecutorService newCachedThreadPool() {
        return new TThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new TSynchronousQueue<>());
    }

    public static TExecutorService newCachedThreadPool(TThreadFactory threadFactory) {
        return new TThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new TSynchronousQueue<>(), threadFactory);
    }

    public static TExecutorService newThreadPerTaskExecutor(TThreadFactory threadFactory) {
        return new ThreadPerTaskExecutor(threadFactory);
    }

    /** One daemon thread per task: the browser has no virtual threads, and needs none. */
    public static TExecutorService newVirtualThreadPerTaskExecutor() {
        return new ThreadPerTaskExecutor(new DaemonThreadFactory());
    }

    public static TScheduledExecutorService newSingleThreadScheduledExecutor() {
        return new DelegatedScheduledExecutorService(new TScheduledThreadPoolExecutor(1));
    }

    public static TScheduledExecutorService newSingleThreadScheduledExecutor(TThreadFactory threadFactory) {
        return new DelegatedScheduledExecutorService(new TScheduledThreadPoolExecutor(1, threadFactory));
    }

    public static TScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return new TScheduledThreadPoolExecutor(corePoolSize);
    }

    public static TScheduledExecutorService newScheduledThreadPool(int corePoolSize, TThreadFactory threadFactory) {
        return new TScheduledThreadPoolExecutor(corePoolSize, threadFactory);
    }

    public static TExecutorService unconfigurableExecutorService(TExecutorService executor) {
        return new DelegatedExecutorService(Objects.requireNonNull(executor));
    }

    public static TScheduledExecutorService unconfigurableScheduledExecutorService(TScheduledExecutorService executor) {
        return new DelegatedScheduledExecutorService(Objects.requireNonNull(executor));
    }

    /** Non-daemon threads named {@code pool-N-thread-M}. (Priorities mean nothing in the browser.) */
    public static TThreadFactory defaultThreadFactory() {
        return new DefaultThreadFactory();
    }

    /** The same as {@link #defaultThreadFactory}: there's no access control to inherit here. */
    @Deprecated(since = "17", forRemoval = true)
    public static TThreadFactory privilegedThreadFactory() {
        return new DefaultThreadFactory();
    }

    public static <T extends @Nullable Object> Callable<T> callable(Runnable task, T result) {
        Objects.requireNonNull(task);
        return new RunnableAdapter<>(task, result);
    }

    public static Callable<@Nullable Object> callable(Runnable task) {
        Objects.requireNonNull(task);
        return new RunnableAdapter<@Nullable Object>(task, null);
    }

    public static Callable<@Nullable Object> callable(PrivilegedAction<?> action) {
        Objects.requireNonNull(action);
        return action::run;
    }

    public static Callable<@Nullable Object> callable(PrivilegedExceptionAction<?> action) {
        Objects.requireNonNull(action);
        return action::run;
    }

    /** Returns {@code callable} itself: there's no access control context to capture here. */
    @Deprecated(since = "17", forRemoval = true)
    @SuppressWarnings("InlineMeSuggester") // mirrors the JDK method; its callers are compiled elsewhere
    public static <T extends @Nullable Object> Callable<T> privilegedCallable(Callable<T> callable) {
        return Objects.requireNonNull(callable);
    }

    /** Returns {@code callable} itself: there's no access control context to capture here. */
    @Deprecated(since = "17", forRemoval = true)
    @SuppressWarnings("InlineMeSuggester") // mirrors the JDK method; its callers are compiled elsewhere
    public static <T extends @Nullable Object> Callable<T> privilegedCallableUsingCurrentClassLoader(
            Callable<T> callable) {
        return Objects.requireNonNull(callable);
    }

    /** A Callable that runs a Runnable and returns a fixed result. */
    private static final class RunnableAdapter<T extends @Nullable Object> implements Callable<T> {
        private final Runnable task;
        private final T result;

        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }

        @Override
        public T call() {
            task.run();
            return result;
        }

        @Override
        public String toString() {
            return super.toString() + "[Wrapped task = " + task + "]";
        }
    }

    /** Numbers its pools and threads the way the JDK's default factory names them. */
    private static final class DefaultThreadFactory implements TThreadFactory {
        private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);

        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String prefix = "pool-" + POOL_NUMBER.getAndIncrement() + "-thread-";

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + threadNumber.getAndIncrement());
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            return t;
        }
    }

    /** Unnamed daemon threads, standing in for virtual threads. */
    private static final class DaemonThreadFactory implements TThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "");
            t.setDaemon(true);
            return t;
        }
    }

    /** Exposes only the {@link TExecutorService} methods of another executor, hiding its setters. */
    private static class DelegatedExecutorService implements TExecutorService {
        private final TExecutorService e;

        DelegatedExecutorService(TExecutorService executor) {
            e = executor;
        }

        @Override
        public void execute(Runnable command) {
            e.execute(command);
        }

        @Override
        public void shutdown() {
            e.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return e.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return e.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return e.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return e.awaitTermination(timeout, unit);
        }

        @Override
        public TFuture<?> submit(Runnable task) {
            return e.submit(task);
        }

        @Override
        public <T extends @Nullable Object> TFuture<T> submit(Callable<T> task) {
            return e.submit(task);
        }

        @Override
        public <T extends @Nullable Object> TFuture<T> submit(Runnable task, T result) {
            return e.submit(task, result);
        }

        @Override
        public <T extends @Nullable Object> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return e.invokeAll(tasks);
        }

        @Override
        public <T extends @Nullable Object> List<TFuture<T>> invokeAll(
                Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return e.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T extends @Nullable Object> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return e.invokeAny(tasks);
        }

        @Override
        public <T extends @Nullable Object> T invokeAny(
                Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TTimeoutException {
            return e.invokeAny(tasks, timeout, unit);
        }

        @Override
        public void close() {
            e.close();
        }
    }

    /** The scheduled counterpart of {@link DelegatedExecutorService}. */
    private static final class DelegatedScheduledExecutorService extends DelegatedExecutorService
            implements TScheduledExecutorService {
        private final TScheduledExecutorService e;

        DelegatedScheduledExecutorService(TScheduledExecutorService executor) {
            super(executor);
            e = executor;
        }

        @Override
        public TScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return e.schedule(command, delay, unit);
        }

        @Override
        public <V extends @Nullable Object> TScheduledFuture<V> schedule(
                Callable<V> callable, long delay, TimeUnit unit) {
            return e.schedule(callable, delay, unit);
        }

        @Override
        public TScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            return e.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override
        public TScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return e.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }

    /** Starts a new thread for every task; terminates once shut down and every thread has ended. */
    private static final class ThreadPerTaskExecutor extends TAbstractExecutorService {
        private final TThreadFactory factory;
        private final Object lock = new Object();

        // Guarded by lock.
        private final HashSet<Thread> threads = new HashSet<>();
        private boolean shutdown;

        ThreadPerTaskExecutor(TThreadFactory factory) {
            this.factory = Objects.requireNonNull(factory);
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command);
            Thread[] self = new Thread[1];
            Thread thread = factory.newThread(() -> {
                try {
                    command.run();
                } finally {
                    threadEnded(self[0]);
                }
            });
            if (thread == null) {
                throw new TRejectedExecutionException("The thread factory refused to create a thread");
            }
            self[0] = thread;
            synchronized (lock) {
                if (shutdown) {
                    throw new TRejectedExecutionException("Executor has been shut down");
                }
                threads.add(thread);
            }
            try {
                thread.start();
            } catch (RuntimeException e) {
                threadEnded(thread);
                throw new TRejectedExecutionException(e);
            }
        }

        private void threadEnded(@Nullable Thread thread) {
            synchronized (lock) {
                threads.remove(thread);
                if (threads.isEmpty()) {
                    lock.notifyAll();
                }
            }
        }

        @Override
        public void shutdown() {
            synchronized (lock) {
                shutdown = true;
                lock.notifyAll();
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Thread> running;
            synchronized (lock) {
                shutdown = true;
                running = new ArrayList<>(threads);
            }
            for (Thread t : running) {
                t.interrupt();
            }
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            synchronized (lock) {
                return shutdown;
            }
        }

        @Override
        public boolean isTerminated() {
            synchronized (lock) {
                return shutdown && threads.isEmpty();
            }
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            synchronized (lock) {
                Monitors.checkInterrupt();
                while (!(shutdown && threads.isEmpty())) {
                    if (nanos <= 0) {
                        return false;
                    }
                    nanos = Monitors.awaitNanos(lock, nanos);
                }
                return true;
            }
        }
    }
}
