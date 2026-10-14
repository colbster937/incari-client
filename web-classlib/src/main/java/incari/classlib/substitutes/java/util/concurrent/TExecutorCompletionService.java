package incari.classlib.substitutes.java.util.concurrent;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.ExecutorCompletionService}: each task's future is queued when it
 * completes, so {@link #take} hands them back in completion order.
 */
public class TExecutorCompletionService<V extends @Nullable Object> implements TCompletionService<V> {

    private final Executor executor;
    private final @Nullable TAbstractExecutorService taskFactory;
    private final BlockingQueue<TFuture<V>> completionQueue;

    public TExecutorCompletionService(Executor executor) {
        this(executor, new TLinkedBlockingQueue<>());
    }

    public TExecutorCompletionService(Executor executor, BlockingQueue<TFuture<V>> completionQueue) {
        this.executor = Objects.requireNonNull(executor);
        this.completionQueue = Objects.requireNonNull(completionQueue);
        // Use the executor's own task type when it has one, as the JDK does.
        this.taskFactory = executor instanceof TAbstractExecutorService service ? service : null;
    }

    @Override
    public TFuture<V> submit(Callable<V> task) {
        Objects.requireNonNull(task);
        TAbstractExecutorService factory = taskFactory;
        return enqueue(factory != null ? factory.newTaskFor(task) : new TFutureTask<>(task));
    }

    @Override
    public TFuture<V> submit(Runnable task, V result) {
        Objects.requireNonNull(task);
        TAbstractExecutorService factory = taskFactory;
        return enqueue(factory != null ? factory.newTaskFor(task, result) : new TFutureTask<>(task, result));
    }

    private TFuture<V> enqueue(TRunnableFuture<V> task) {
        executor.execute(new QueueingFuture<>(task, completionQueue));
        return task;
    }

    @Override
    public TFuture<V> take() throws InterruptedException {
        return completionQueue.take();
    }

    @Override
    public @Nullable TFuture<V> poll() {
        return completionQueue.poll();
    }

    @Override
    public @Nullable TFuture<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

    /** Runs the task, then queues it once it's done (normally, exceptionally or cancelled). */
    private static final class QueueingFuture<V extends @Nullable Object> extends TFutureTask<@Nullable Void> {

        private final TFuture<V> task;
        private final BlockingQueue<TFuture<V>> queue;

        QueueingFuture(TRunnableFuture<V> task, BlockingQueue<TFuture<V>> queue) {
            super(task, null);
            this.task = task;
            this.queue = queue;
        }

        @Override
        protected void done() {
            queue.add(task);
        }
    }
}
