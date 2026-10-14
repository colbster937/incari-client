package incari.classlib.substitutes.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.CompletableFuture}, written for a page where Java threads are
 * coroutines on one thread.
 *
 * <p>Waiting ({@link #get}, {@link #join}) is a monitor {@code wait}, which suspends the coroutine
 * rather than spinning; completing wakes it with {@code notifyAll}. The default executor starts a
 * {@link Thread} per task: a coroutine is cheap, and there are no real threads to pool. Timeouts
 * ({@link #orTimeout}, {@link #completeOnTimeout}, {@link #delayedExecutor}) are a watcher thread
 * that sleeps, costing only a pending timer on the page.
 *
 * <p>Dependents are kept in a plain list per future and run after the lock is released, in the
 * thread that completes the future (or in the registering thread if it's already complete), so a
 * callback can never deadlock against the future it's attached to.
 *
 * @param <T> the result type
 */
public class TCompletableFuture<T extends @Nullable Object> implements Future<T>, TCompletionStage<T> {

    /** The encoded result for a {@code null} value, so that {@code null} can mean "not done". */
    private static final Object NIL = new Object();

    private static final Executor DEFAULT_EXECUTOR = new ThreadPerTaskExecutor();

    /** Guards {@link #result}'s transition from {@code null} and {@link #dependents}. */
    private final Object lock = new Object();

    /** {@code null} until done; then {@link #NIL}, the value, or a {@link Failure}. */
    private volatile @Nullable Object result;

    /** Actions waiting for completion; {@code null} once done or while there are none. */
    private @Nullable List<Consumer<Object>> dependents;

    public TCompletableFuture() {}

    private TCompletableFuture(Object encodedResult) {
        this.result = encodedResult;
    }

    // ---------------------------------------------------------------------------------------------
    // Static factories
    // ---------------------------------------------------------------------------------------------

    public static <U extends @Nullable Object> TCompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return supplyAsync(supplier, DEFAULT_EXECUTOR);
    }

    public static <U extends @Nullable Object> TCompletableFuture<U> supplyAsync(
            Supplier<U> supplier, Executor executor) {
        Objects.requireNonNull(supplier);
        TCompletableFuture<U> future = new TCompletableFuture<>();
        future.runOn(executor, () -> encode(supplier.get()), true);
        return future;
    }

    public static TCompletableFuture<Void> runAsync(Runnable runnable) {
        return runAsync(runnable, DEFAULT_EXECUTOR);
    }

    public static TCompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        Objects.requireNonNull(runnable);
        TCompletableFuture<Void> future = new TCompletableFuture<>();
        future.runOn(
                executor,
                () -> {
                    runnable.run();
                    return NIL;
                },
                true);
        return future;
    }

    public static <U extends @Nullable Object> TCompletableFuture<U> completedFuture(U value) {
        return new TCompletableFuture<>(encode(value));
    }

    public static <U extends @Nullable Object> TCompletionStage<U> completedStage(U value) {
        return new MinimalStage<>(encode(value));
    }

    public static <U extends @Nullable Object> TCompletableFuture<U> failedFuture(Throwable ex) {
        return new TCompletableFuture<>(new Failure(Objects.requireNonNull(ex)));
    }

    public static <U extends @Nullable Object> TCompletionStage<U> failedStage(Throwable ex) {
        return new MinimalStage<>(new Failure(Objects.requireNonNull(ex)));
    }

    /**
     * Completes when every given future has. If any failed, completes exceptionally with a
     * {@link TCompletionException} for the first failure in argument order.
     */
    public static TCompletableFuture<Void> allOf(TCompletableFuture<?>... cfs) {
        for (TCompletableFuture<?> cf : cfs) {
            Objects.requireNonNull(cf);
        }
        TCompletableFuture<Void> all = new TCompletableFuture<>();
        if (cfs.length == 0) {
            all.completeWith(NIL);
            return all;
        }
        AtomicInteger remaining = new AtomicInteger(cfs.length);
        Consumer<Object> countDown = ignored -> {
            if (remaining.decrementAndGet() != 0) {
                return;
            }
            // The last one in: every result is set, and the atomic makes them visible here.
            for (TCompletableFuture<?> cf : cfs) {
                if (cf.result instanceof Failure failure) {
                    all.completeWith(failure.propagated());
                    return;
                }
            }
            all.completeWith(NIL);
        };
        for (TCompletableFuture<?> cf : cfs) {
            cf.onDone(countDown);
        }
        return all;
    }

    /** Completes like the first of the given futures to complete; never, if there are none. */
    public static TCompletableFuture<Object> anyOf(TCompletableFuture<?>... cfs) {
        for (TCompletableFuture<?> cf : cfs) {
            Objects.requireNonNull(cf);
        }
        TCompletableFuture<Object> any = new TCompletableFuture<>();
        for (TCompletableFuture<?> cf : cfs) {
            cf.onDone(r -> any.completeWith(relayed(r)));
            if (any.result != null) {
                break; // Already decided; don't leave actions on the rest.
            }
        }
        return any;
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit) {
        return delayedExecutor(delay, unit, DEFAULT_EXECUTOR);
    }

    /** An executor that hands each task to {@code executor} after the delay (a sleeping thread). */
    public static Executor delayedExecutor(long delay, TimeUnit unit, Executor executor) {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(executor);
        long nanos = unit.toNanos(delay);
        return task -> {
            Objects.requireNonNull(task);
            startDaemon(() -> {
                if (sleepNanos(nanos)) {
                    executor.execute(task);
                }
            });
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Future: reading the result
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean isDone() {
        return result != null;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        Object r = result;
        if (r == null) {
            r = await(Long.MAX_VALUE, true);
        }
        return reportGet(Objects.requireNonNull(r));
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Object r = result;
        if (r == null) {
            r = await(unit.toNanos(timeout), true);
            if (r == null) {
                throw new TimeoutException();
            }
        }
        return reportGet(r);
    }

    /** Like {@link #get()}, but unchecked: failures come as {@link TCompletionException}. */
    public T join() {
        Object r = result;
        if (r == null) {
            try {
                r = await(Long.MAX_VALUE, false);
            } catch (InterruptedException e) {
                throw new AssertionError("uninterruptible wait was interrupted", e);
            }
        }
        return reportJoin(Objects.requireNonNull(r));
    }

    public T getNow(T valueIfAbsent) {
        Object r = result;
        return r == null ? valueIfAbsent : reportJoin(r);
    }

    @Override
    public T resultNow() {
        Object r = result;
        if (r == null) {
            throw new IllegalStateException("Task has not completed");
        }
        if (r instanceof Failure failure) {
            throw new IllegalStateException(
                    failure.cause instanceof CancellationException
                            ? "Task was cancelled"
                            : "Task completed with exception");
        }
        return decode(r);
    }

    @Override
    public Throwable exceptionNow() {
        Object r = result;
        if (r == null) {
            throw new IllegalStateException("Task has not completed");
        }
        if (!(r instanceof Failure failure)) {
            throw new IllegalStateException("Task completed with a result");
        }
        if (failure.cause instanceof CancellationException) {
            throw new IllegalStateException("Task was cancelled");
        }
        // The documented contract reports what the computation threw, not our wrapper.
        Throwable cause = failure.cause.getCause();
        return failure.cause instanceof TCompletionException && cause != null ? cause : failure.cause;
    }

    @Override
    public Future.State state() {
        Object r = result;
        if (r == null) {
            return Future.State.RUNNING;
        }
        if (r instanceof Failure failure) {
            return failure.cause instanceof CancellationException ? Future.State.CANCELLED : Future.State.FAILED;
        }
        return Future.State.SUCCESS;
    }

    @Override
    public boolean isCancelled() {
        return result instanceof Failure failure && failure.cause instanceof CancellationException;
    }

    public boolean isCompletedExceptionally() {
        return result instanceof Failure;
    }

    public int getNumberOfDependents() {
        return pendingCount();
    }

    private int pendingCount() {
        synchronized (lock) {
            List<Consumer<Object>> pending = dependents;
            return pending == null ? 0 : pending.size();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Completing
    // ---------------------------------------------------------------------------------------------

    public boolean complete(T value) {
        return completeWith(encode(value));
    }

    public boolean completeExceptionally(Throwable ex) {
        return completeWith(new Failure(Objects.requireNonNull(ex)));
    }

    /** Completes with a {@link CancellationException}; interrupting has no meaning here. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = completeWith(new Failure(new CancellationException()));
        return cancelled || isCancelled();
    }

    public TCompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        return completeAsync(supplier, defaultExecutor());
    }

    public TCompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        Objects.requireNonNull(supplier);
        runOn(executor, () -> encode(supplier.get()), true);
        return this;
    }

    /** Fails with a {@link TimeoutException} unless done within the timeout. */
    public TCompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit);
        watchTimeout(unit.toNanos(timeout), () -> new Failure(new TimeoutException()));
        return this;
    }

    public TCompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit);
        watchTimeout(unit.toNanos(timeout), () -> encode(value));
        return this;
    }

    public void obtrudeValue(T value) {
        obtrude(encode(value));
    }

    public void obtrudeException(Throwable ex) {
        obtrude(new Failure(Objects.requireNonNull(ex)));
    }

    // ---------------------------------------------------------------------------------------------
    // CompletionStage
    // ---------------------------------------------------------------------------------------------

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return applyStage(null, fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return applyStage(defaultExecutor(), fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> thenApplyAsync(
            Function<? super T, ? extends U> fn, Executor executor) {
        return applyStage(Objects.requireNonNull(executor), fn);
    }

    @Override
    public TCompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return acceptStage(null, action);
    }

    @Override
    public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return acceptStage(defaultExecutor(), action);
    }

    @Override
    public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return acceptStage(Objects.requireNonNull(executor), action);
    }

    @Override
    public TCompletableFuture<Void> thenRun(Runnable action) {
        return runStage(null, action);
    }

    @Override
    public TCompletableFuture<Void> thenRunAsync(Runnable action) {
        return runStage(defaultExecutor(), action);
    }

    @Override
    public TCompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return runStage(Objects.requireNonNull(executor), action);
    }

    @Override
    public <U extends @Nullable Object, V extends @Nullable Object> TCompletableFuture<V> thenCombine(
            TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        return combineStage(null, other, fn);
    }

    @Override
    public <U extends @Nullable Object, V extends @Nullable Object> TCompletableFuture<V> thenCombineAsync(
            TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        return combineStage(defaultExecutor(), other, fn);
    }

    @Override
    public <U extends @Nullable Object, V extends @Nullable Object> TCompletableFuture<V> thenCombineAsync(
            TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        return combineStage(Objects.requireNonNull(executor), other, fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<Void> thenAcceptBoth(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return acceptBothStage(null, other, action);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<Void> thenAcceptBothAsync(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return acceptBothStage(defaultExecutor(), other, action);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<Void> thenAcceptBothAsync(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        return acceptBothStage(Objects.requireNonNull(executor), other, action);
    }

    @Override
    public TCompletableFuture<Void> runAfterBoth(TCompletionStage<?> other, Runnable action) {
        return runBothStage(null, other, action);
    }

    @Override
    public TCompletableFuture<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action) {
        return runBothStage(defaultExecutor(), other, action);
    }

    @Override
    public TCompletableFuture<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action, Executor executor) {
        return runBothStage(Objects.requireNonNull(executor), other, action);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> applyToEither(
            TCompletionStage<? extends T> other, Function<? super T, U> fn) {
        return eitherStage(null, other, fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> applyToEitherAsync(
            TCompletionStage<? extends T> other, Function<? super T, U> fn) {
        return eitherStage(defaultExecutor(), other, fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> applyToEitherAsync(
            TCompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
        return eitherStage(Objects.requireNonNull(executor), other, fn);
    }

    @Override
    public TCompletableFuture<Void> acceptEither(TCompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEitherStage(null, other, action);
    }

    @Override
    public TCompletableFuture<Void> acceptEitherAsync(TCompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEitherStage(defaultExecutor(), other, action);
    }

    @Override
    public TCompletableFuture<Void> acceptEitherAsync(
            TCompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        return acceptEitherStage(Objects.requireNonNull(executor), other, action);
    }

    @Override
    public TCompletableFuture<Void> runAfterEither(TCompletionStage<?> other, Runnable action) {
        return runEitherStage(null, other, action);
    }

    @Override
    public TCompletableFuture<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action) {
        return runEitherStage(defaultExecutor(), other, action);
    }

    @Override
    public TCompletableFuture<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action, Executor executor) {
        return runEitherStage(Objects.requireNonNull(executor), other, action);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> thenCompose(
            Function<? super T, ? extends TCompletionStage<U>> fn) {
        return composeStage(null, fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> thenComposeAsync(
            Function<? super T, ? extends TCompletionStage<U>> fn) {
        return composeStage(defaultExecutor(), fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> thenComposeAsync(
            Function<? super T, ? extends TCompletionStage<U>> fn, Executor executor) {
        return composeStage(Objects.requireNonNull(executor), fn);
    }

    @Override
    public TCompletableFuture<T> whenComplete(BiConsumer<? super T, ? super @Nullable Throwable> action) {
        return whenCompleteStage(null, action);
    }

    @Override
    public TCompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super @Nullable Throwable> action) {
        return whenCompleteStage(defaultExecutor(), action);
    }

    @Override
    public TCompletableFuture<T> whenCompleteAsync(
            BiConsumer<? super T, ? super @Nullable Throwable> action, Executor executor) {
        return whenCompleteStage(Objects.requireNonNull(executor), action);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> handle(
            BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
        return handleStage(null, fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> handleAsync(
            BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
        return handleStage(defaultExecutor(), fn);
    }

    @Override
    public <U extends @Nullable Object> TCompletableFuture<U> handleAsync(
            BiFunction<? super T, @Nullable Throwable, ? extends U> fn, Executor executor) {
        return handleStage(Objects.requireNonNull(executor), fn);
    }

    @Override
    public TCompletableFuture<T> toCompletableFuture() {
        return this;
    }

    @Override
    public TCompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return exceptionallyStage(null, fn);
    }

    @Override
    public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
        return exceptionallyStage(defaultExecutor(), fn);
    }

    @Override
    public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
        return exceptionallyStage(Objects.requireNonNull(executor), fn);
    }

    @Override
    public TCompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends TCompletionStage<T>> fn) {
        return exceptionallyComposeStage(null, fn);
    }

    @Override
    public TCompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn) {
        return exceptionallyComposeStage(defaultExecutor(), fn);
    }

    @Override
    public TCompletableFuture<T> exceptionallyComposeAsync(
            Function<Throwable, ? extends TCompletionStage<T>> fn, Executor executor) {
        return exceptionallyComposeStage(Objects.requireNonNull(executor), fn);
    }

    // ---------------------------------------------------------------------------------------------
    // Extension points and the rest
    // ---------------------------------------------------------------------------------------------

    /** The future every stage method returns; subclasses override it to return their own type. */
    public <U extends @Nullable Object> TCompletableFuture<U> newIncompleteFuture() {
        return new TCompletableFuture<>();
    }

    /** Starts a new thread per task: in the browser, each task a coroutine. */
    public Executor defaultExecutor() {
        return DEFAULT_EXECUTOR;
    }

    /** A new future completed the same way, with failures wrapped in {@link TCompletionException}. */
    public TCompletableFuture<T> copy() {
        TCompletableFuture<T> copy = newIncompleteFuture();
        onDone(r -> copy.completeWith(relayed(r)));
        return copy;
    }

    /** A stage completed like this one that supports only the {@link TCompletionStage} methods. */
    public TCompletionStage<T> minimalCompletionStage() {
        TCompletableFuture<T> stage = new MinimalStage<>();
        onDone(r -> stage.completeWith(relayed(r)));
        return stage;
    }

    @Override
    public String toString() {
        Object r = result;
        String state;
        if (r == null) {
            int count = pendingCount();
            state = count == 0 ? "[Not completed]" : "[Not completed, " + count + " dependents]";
        } else if (r instanceof Failure failure) {
            state = "[Completed exceptionally: " + failure.cause + "]";
        } else {
            state = "[Completed normally]";
        }
        return super.toString() + state;
    }

    // ---------------------------------------------------------------------------------------------
    // Core: completion and dependents
    // ---------------------------------------------------------------------------------------------

    /**
     * Sets the result if none is set yet, wakes waiters, then runs the dependents outside the lock
     * so they may freely touch this future (or block) without deadlocking.
     */
    private boolean completeWith(Object encoded) {
        List<Consumer<Object>> toRun;
        synchronized (lock) {
            if (result != null) {
                return false;
            }
            result = encoded;
            toRun = dependents;
            dependents = null;
            lock.notifyAll();
        }
        if (toRun != null) {
            for (Consumer<Object> dependent : toRun) {
                dependent.accept(encoded);
            }
        }
        return true;
    }

    private void obtrude(Object encoded) {
        List<Consumer<Object>> toRun;
        synchronized (lock) {
            result = encoded;
            toRun = dependents;
            dependents = null;
            lock.notifyAll();
        }
        if (toRun != null) {
            for (Consumer<Object> dependent : toRun) {
                dependent.accept(encoded);
            }
        }
    }

    /** Runs {@code action} with the result once there is one: now if done, else on completion. */
    private void onDone(Consumer<Object> action) {
        Object r;
        synchronized (lock) {
            r = result;
            if (r == null) {
                List<Consumer<Object>> pending = dependents;
                if (pending == null) {
                    pending = new ArrayList<>(2);
                    dependents = pending;
                }
                pending.add(action);
                return;
            }
        }
        action.accept(r);
    }

    /**
     * Completes this future with what {@code body} computes, directly or on {@code executor}. A body
     * that returns {@code null} has arranged completion itself (compose stages). A throwing body
     * completes this future with the throwable wrapped in a {@link TCompletionException}.
     */
    private void runOn(@Nullable Executor executor, Body body, boolean rethrowRejection) {
        Runnable task = () -> {
            Object encoded;
            try {
                encoded = body.compute();
            } catch (Throwable x) {
                encoded = new Failure(wrap(x));
            }
            if (encoded != null) {
                completeWith(encoded);
            }
        };
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.execute(task);
        } catch (RuntimeException | Error rejected) {
            // supplyAsync and friends report a refusing executor to their caller; a dependent stage
            // has no caller left to tell, so the stage fails with it instead.
            if (rethrowRejection) {
                throw rejected;
            }
            completeWith(new Failure(wrap(rejected)));
        }
    }

    /** Waits for the result, at most {@code nanos}; {@code null} on timeout. */
    private @Nullable Object await(long nanos, boolean interruptible) throws InterruptedException {
        boolean interrupted = false;
        try {
            synchronized (lock) {
                long deadline = System.nanoTime() + nanos;
                while (true) {
                    Object r = result;
                    if (r != null) {
                        return r;
                    }
                    long left = nanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
                    if (left <= 0) {
                        return null;
                    }
                    try {
                        // wait() blocks the coroutine (browser) or thread (JVM) until notifyAll.
                        if (left == Long.MAX_VALUE) {
                            lock.wait();
                        } else {
                            lock.wait(left / 1_000_000 + 1);
                        }
                    } catch (InterruptedException e) {
                        if (interruptible) {
                            throw e;
                        }
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Completes with {@code outcome} if still incomplete after {@code nanos}. */
    private void watchTimeout(long nanos, Supplier<Object> outcome) {
        if (result != null) {
            return;
        }
        // Waiting on the lock (not sleeping) lets the watcher end as soon as the future completes.
        startDaemon(() -> {
            try {
                if (await(nanos, true) == null) {
                    completeWith(outcome.get());
                }
            } catch (InterruptedException e) {
                // Nobody interrupts the watcher; if something does, it just stops watching.
                Thread.currentThread().interrupt();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Stage builders
    // ---------------------------------------------------------------------------------------------

    /** A dependent whose body runs only on success; failures pass straight through, wrapped. */
    private <U extends @Nullable Object> TCompletableFuture<U> onSuccess(
            @Nullable Executor executor, Function<Object, @Nullable Object> body) {
        TCompletableFuture<U> dst = newIncompleteFuture();
        onDone(r -> {
            if (r instanceof Failure failure) {
                dst.completeWith(failure.propagated());
            } else {
                dst.runOn(executor, () -> body.apply(r), false);
            }
        });
        return dst;
    }

    private <U extends @Nullable Object> TCompletableFuture<U> applyStage(
            @Nullable Executor executor, Function<? super T, ? extends U> fn) {
        Objects.requireNonNull(fn);
        return onSuccess(executor, r -> encode(fn.apply(TCompletableFuture.<T>decode(r))));
    }

    private TCompletableFuture<Void> acceptStage(@Nullable Executor executor, Consumer<? super T> action) {
        Objects.requireNonNull(action);
        return onSuccess(executor, r -> {
            action.accept(TCompletableFuture.<T>decode(r));
            return NIL;
        });
    }

    private TCompletableFuture<Void> runStage(@Nullable Executor executor, Runnable action) {
        Objects.requireNonNull(action);
        return onSuccess(executor, r -> {
            action.run();
            return NIL;
        });
    }

    private <U extends @Nullable Object> TCompletableFuture<U> composeStage(
            @Nullable Executor executor, Function<? super T, ? extends TCompletionStage<U>> fn) {
        Objects.requireNonNull(fn);
        TCompletableFuture<U> dst = newIncompleteFuture();
        onDone(r -> {
            if (r instanceof Failure failure) {
                dst.completeWith(failure.propagated());
            } else {
                dst.runOn(
                        executor,
                        () -> {
                            dst.follow(fn.apply(TCompletableFuture.<T>decode(r)));
                            return null;
                        },
                        false);
            }
        });
        return dst;
    }

    /** Completes this future like {@code stage} once it completes; a null stage is an NPE. */
    private void follow(@Nullable TCompletionStage<?> stage) {
        TCompletableFuture<?> source = Objects.requireNonNull(stage, "stage").toCompletableFuture();
        source.onDone(s -> completeWith(relayed(s)));
    }

    private <U extends @Nullable Object, V extends @Nullable Object> TCompletableFuture<V> bothStage(
            @Nullable Executor executor, TCompletionStage<? extends U> other, BiFunction<Object, Object, Object> body) {
        TCompletableFuture<? extends U> second = other.toCompletableFuture();
        TCompletableFuture<V> dst = newIncompleteFuture();
        // Nested registration: the body runs once, when the later of the two completes.
        onDone(r -> second.onDone(s -> {
            if (r instanceof Failure failure) {
                dst.completeWith(failure.propagated());
            } else if (s instanceof Failure failure) {
                dst.completeWith(failure.propagated());
            } else {
                dst.runOn(executor, () -> body.apply(r, s), false);
            }
        }));
        return dst;
    }

    private <U extends @Nullable Object, V extends @Nullable Object> TCompletableFuture<V> combineStage(
            @Nullable Executor executor,
            TCompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        Objects.requireNonNull(fn);
        return bothStage(
                executor,
                other,
                (r, s) -> encode(fn.apply(TCompletableFuture.<T>decode(r), TCompletableFuture.<U>decode(s))));
    }

    private <U extends @Nullable Object> TCompletableFuture<Void> acceptBothStage(
            @Nullable Executor executor, TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        Objects.requireNonNull(action);
        return bothStage(executor, other, (r, s) -> {
            action.accept(TCompletableFuture.<T>decode(r), TCompletableFuture.<U>decode(s));
            return NIL;
        });
    }

    private TCompletableFuture<Void> runBothStage(
            @Nullable Executor executor, TCompletionStage<?> other, Runnable action) {
        Objects.requireNonNull(action);
        return bothStage(executor, other, (r, s) -> {
            action.run();
            return NIL;
        });
    }

    private <U extends @Nullable Object> TCompletableFuture<U> eitherBody(
            @Nullable Executor executor, TCompletionStage<?> other, Function<Object, Object> body) {
        TCompletableFuture<?> second = other.toCompletableFuture();
        TCompletableFuture<U> dst = newIncompleteFuture();
        AtomicBoolean claimed = new AtomicBoolean();
        Consumer<Object> first = r -> {
            // Both sources call this; only the first to complete may run the body.
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            if (r instanceof Failure failure) {
                dst.completeWith(failure.propagated());
            } else {
                dst.runOn(executor, () -> body.apply(r), false);
            }
        };
        onDone(first);
        second.onDone(first);
        return dst;
    }

    private <U extends @Nullable Object> TCompletableFuture<U> eitherStage(
            @Nullable Executor executor, TCompletionStage<? extends T> other, Function<? super T, U> fn) {
        Objects.requireNonNull(fn);
        return eitherBody(executor, other, r -> encode(fn.apply(TCompletableFuture.<T>decode(r))));
    }

    private TCompletableFuture<Void> acceptEitherStage(
            @Nullable Executor executor, TCompletionStage<? extends T> other, Consumer<? super T> action) {
        Objects.requireNonNull(action);
        return eitherBody(executor, other, r -> {
            action.accept(TCompletableFuture.<T>decode(r));
            return NIL;
        });
    }

    private TCompletableFuture<Void> runEitherStage(
            @Nullable Executor executor, TCompletionStage<?> other, Runnable action) {
        Objects.requireNonNull(action);
        return eitherBody(executor, other, r -> {
            action.run();
            return NIL;
        });
    }

    private <U extends @Nullable Object> TCompletableFuture<U> handleStage(
            @Nullable Executor executor, BiFunction<? super T, @Nullable Throwable, ? extends U> fn) {
        Objects.requireNonNull(fn);
        TCompletableFuture<U> dst = newIncompleteFuture();
        onDone(r -> dst.runOn(
                executor,
                () -> r instanceof Failure failure
                        ? encode(fn.apply(null, failure.cause))
                        : encode(fn.apply(TCompletableFuture.<T>decode(r), null)),
                false));
        return dst;
    }

    // Identity is the point below: an action that rethrows the source's own failure mustn't be
    // added to it as suppressed (Throwable forbids self-suppression).
    @SuppressWarnings("ReferenceEquality")
    private TCompletableFuture<T> whenCompleteStage(
            @Nullable Executor executor, BiConsumer<? super T, ? super @Nullable Throwable> action) {
        Objects.requireNonNull(action);
        TCompletableFuture<T> dst = newIncompleteFuture();
        onDone(r -> dst.runOn(
                executor,
                () -> {
                    if (!(r instanceof Failure failure)) {
                        // A throwing action fails the stage; runOn wraps what it throws.
                        action.accept(TCompletableFuture.<T>decode(r), null);
                        return r;
                    }
                    try {
                        action.accept(null, failure.cause);
                    } catch (Throwable actionFailure) {
                        // The source's failure wins; the action's rides along, as documented.
                        if (actionFailure != failure.cause) {
                            failure.cause.addSuppressed(actionFailure);
                        }
                    }
                    return failure.propagated();
                },
                false));
        return dst;
    }

    private TCompletableFuture<T> exceptionallyStage(@Nullable Executor executor, Function<Throwable, ? extends T> fn) {
        Objects.requireNonNull(fn);
        TCompletableFuture<T> dst = newIncompleteFuture();
        onDone(r -> {
            if (r instanceof Failure failure) {
                dst.runOn(executor, () -> encode(fn.apply(failure.cause)), false);
            } else {
                dst.completeWith(r);
            }
        });
        return dst;
    }

    private TCompletableFuture<T> exceptionallyComposeStage(
            @Nullable Executor executor, Function<Throwable, ? extends TCompletionStage<T>> fn) {
        Objects.requireNonNull(fn);
        TCompletableFuture<T> dst = newIncompleteFuture();
        onDone(r -> {
            if (r instanceof Failure failure) {
                dst.runOn(
                        executor,
                        () -> {
                            dst.follow(fn.apply(failure.cause));
                            return null;
                        },
                        false);
            } else {
                dst.completeWith(r);
            }
        });
        return dst;
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding and reporting
    // ---------------------------------------------------------------------------------------------

    private static Object encode(@Nullable Object value) {
        return value == null ? NIL : value;
    }

    // A value only gets in through encode(...) of the type the caller asks back for, so the cast is
    // safe, and the type parameter exists to spare every caller that cast; generics can't express it.
    // NIL is compared by identity (it's a sentinel), and it stands for a null of that same type,
    // which NullAway can't know is nullable.
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals", "ReferenceEquality", "NullAway"})
    private static <V extends @Nullable Object> V decode(Object encoded) {
        return encoded == NIL ? (V) null : (V) encoded;
    }

    /** What a dependent that just passes a result on stores: failures wrapped, values as they are. */
    private static Object relayed(Object encoded) {
        return encoded instanceof Failure failure ? failure.propagated() : encoded;
    }

    private static Throwable wrap(Throwable x) {
        return x instanceof TCompletionException ? x : new TCompletionException(x);
    }

    private T reportJoin(Object encoded) {
        if (encoded instanceof Failure failure) {
            Throwable x = failure.cause;
            if (x instanceof CancellationException cancelled) {
                throw cancelled;
            }
            if (x instanceof TCompletionException completion) {
                throw completion;
            }
            throw new TCompletionException(x);
        }
        return decode(encoded);
    }

    private T reportGet(Object encoded) throws ExecutionException {
        if (encoded instanceof Failure failure) {
            Throwable x = failure.cause;
            if (x instanceof CancellationException cancelled) {
                throw cancelled;
            }
            Throwable cause = x.getCause();
            // get() reports what the computation threw, under ExecutionException.
            throw new ExecutionException(x instanceof TCompletionException && cause != null ? cause : x);
        }
        return decode(encoded);
    }

    private static void startDaemon(Runnable task) {
        Thread thread = new Thread(task, "CompletableFuture timer");
        // Timers must never keep a desktop JVM alive; the browser ignores the flag.
        thread.setDaemon(true);
        thread.start();
    }

    /** Sleeps; {@code false} if interrupted, which cancels whatever was going to follow. */
    private static boolean sleepNanos(long nanos) {
        if (nanos <= 0) {
            return true;
        }
        try {
            // TeaVM's Thread has only sleep(millis); round up so the delay is never short.
            Thread.sleep(nanos / 1_000_000 + (nanos % 1_000_000 == 0 ? 0 : 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Nested helpers
    // ---------------------------------------------------------------------------------------------

    /** A stage computation; returns the encoded result, or {@code null} if it completes the stage itself. */
    @FunctionalInterface
    private interface Body {
        @Nullable
        Object compute() throws Throwable;
    }

    /** The encoded form of an exceptional result. */
    private static final class Failure {
        final Throwable cause;

        Failure(Throwable cause) {
            this.cause = cause;
        }

        /** How a dependent stage records this: wrapped in a CompletionException unless it is one. */
        Failure propagated() {
            return cause instanceof TCompletionException ? this : new Failure(new TCompletionException(cause));
        }
    }

    /** The default executor: one new thread (in the browser, one coroutine) per task. */
    private static final class ThreadPerTaskExecutor implements Executor {
        @Override
        public void execute(Runnable task) {
            Objects.requireNonNull(task);
            Thread thread = new Thread(task, "CompletableFuture async");
            // Like the JDK's pool threads: async work never keeps a desktop JVM alive.
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * What {@link #minimalCompletionStage}, {@link #completedStage} and {@link #failedStage} return:
     * only the {@link TCompletionStage} methods work; the rest throw
     * {@link UnsupportedOperationException}. {@link #toCompletableFuture} gives a full future.
     */
    private static final class MinimalStage<T extends @Nullable Object> extends TCompletableFuture<T> {

        MinimalStage() {}

        MinimalStage(Object encodedResult) {
            super(encodedResult);
        }

        @Override
        public <U extends @Nullable Object> TCompletableFuture<U> newIncompleteFuture() {
            return new MinimalStage<>();
        }

        @Override
        public TCompletableFuture<T> toCompletableFuture() {
            TCompletableFuture<T> full = new TCompletableFuture<>();
            ((TCompletableFuture<T>) this).onDone(full::completeWith);
            return full;
        }

        @Override
        public T get() {
            throw unsupported();
        }

        @Override
        public T get(long timeout, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public T getNow(T valueIfAbsent) {
            throw unsupported();
        }

        @Override
        public T join() {
            throw unsupported();
        }

        @Override
        public T resultNow() {
            throw unsupported();
        }

        @Override
        public Throwable exceptionNow() {
            throw unsupported();
        }

        @Override
        public boolean complete(T value) {
            throw unsupported();
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            throw unsupported();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            throw unsupported();
        }

        @Override
        public void obtrudeValue(T value) {
            throw unsupported();
        }

        @Override
        public void obtrudeException(Throwable ex) {
            throw unsupported();
        }

        @Override
        public boolean isDone() {
            throw unsupported();
        }

        @Override
        public boolean isCancelled() {
            throw unsupported();
        }

        @Override
        public boolean isCompletedExceptionally() {
            throw unsupported();
        }

        @Override
        public Future.State state() {
            throw unsupported();
        }

        @Override
        public int getNumberOfDependents() {
            throw unsupported();
        }

        @Override
        public TCompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
            throw unsupported();
        }

        @Override
        public TCompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
            throw unsupported();
        }

        @Override
        public TCompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public TCompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("a minimal CompletionStage supports only CompletionStage methods");
        }
    }
}
