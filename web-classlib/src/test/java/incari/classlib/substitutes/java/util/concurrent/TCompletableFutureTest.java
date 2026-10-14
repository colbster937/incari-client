package incari.classlib.substitutes.java.util.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// These run on a desktop JVM with real threads; the browser build runs the same code on coroutines.
@Timeout(30)
class TCompletableFutureTest {

    /** Runs each task on a fresh thread and counts them, to tell executor use from direct calls. */
    private static final class CountingExecutor implements Executor {
        final AtomicInteger tasks = new AtomicInteger();
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        @Override
        public void execute(Runnable task) {
            tasks.incrementAndGet();
            Thread thread = new Thread(() -> {
                threads.add(Thread.currentThread());
                task.run();
            });
            thread.start();
        }
    }

    // --- Completing and reading -----------------------------------------------------------------

    @Test
    void completeThenJoinGetAndGetNow() throws Exception {
        TCompletableFuture<String> future = new TCompletableFuture<>();
        assertThat(future.isDone()).isFalse();
        assertThat(future.getNow("absent")).isEqualTo("absent");
        assertThat(future.state()).isEqualTo(Future.State.RUNNING);

        assertThat(future.complete("value")).isTrue();
        assertThat(future.complete("other")).isFalse();
        assertThat(future.completeExceptionally(new RuntimeException())).isFalse();

        assertThat(future.isDone()).isTrue();
        assertThat(future.join()).isEqualTo("value");
        assertThat(future.get()).isEqualTo("value");
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("value");
        assertThat(future.getNow("absent")).isEqualTo("value");
        assertThat(future.resultNow()).isEqualTo("value");
        assertThat(future.state()).isEqualTo(Future.State.SUCCESS);
        assertThat(future.isCompletedExceptionally()).isFalse();
        assertThatThrownBy(future::exceptionNow).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullIsAValue() {
        TCompletableFuture<String> future = TCompletableFuture.completedFuture(null);
        assertThat(future.isDone()).isTrue();
        assertThat(future.join()).isNull();
        assertThat(future.getNow("absent")).isNull();
        assertThat(future.thenApply(v -> v == null).join()).isTrue();
    }

    @Test
    void joinBlocksUntilAnotherThreadCompletes() throws Exception {
        TCompletableFuture<Integer> future = new TCompletableFuture<>();
        Thread completer = new Thread(() -> {
            sleep(50);
            future.complete(42);
        });
        completer.start();
        assertThat(future.join()).isEqualTo(42);
        completer.join();
    }

    @Test
    void getWithTimeoutTimesOut() {
        TCompletableFuture<Integer> future = new TCompletableFuture<>();
        assertThatThrownBy(() -> future.get(20, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    @Test
    void failureIsReportedAsCompletionAndExecutionException() {
        IllegalStateException boom = new IllegalStateException("boom");
        TCompletableFuture<String> future = new TCompletableFuture<>();
        assertThat(future.completeExceptionally(boom)).isTrue();

        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThat(future.state()).isEqualTo(Future.State.FAILED);
        assertThat(future.exceptionNow()).isSameAs(boom);
        assertThatThrownBy(future::resultNow).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(future::join)
                .isInstanceOf(TCompletionException.class)
                .hasCause(boom);
        assertThatThrownBy(() -> future.getNow("x"))
                .isInstanceOf(TCompletionException.class)
                .hasCause(boom);
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class).hasCause(boom);
    }

    @Test
    void failedFutureAndStage() {
        RuntimeException boom = new RuntimeException("boom");
        TCompletableFuture<String> failed = TCompletableFuture.failedFuture(boom);
        assertThat(failed.exceptionNow()).isSameAs(boom);

        TCompletionStage<String> stage = TCompletableFuture.failedStage(boom);
        assertThatThrownBy(() -> stage.toCompletableFuture().join()).hasCause(boom);
        assertThat(stage.exceptionally(x -> "recovered").toCompletableFuture().join())
                .isEqualTo("recovered");
    }

    @Test
    void minimalStagesRefuseFutureMethods() {
        TCompletionStage<String> stage = TCompletableFuture.completedStage("v");
        TCompletableFuture<String> asFuture = (TCompletableFuture<String>) stage;
        assertThatThrownBy(asFuture::join).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asFuture.complete("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(asFuture::isDone).isInstanceOf(UnsupportedOperationException.class);

        TCompletionStage<Integer> mapped = stage.thenApply(String::length);
        assertThatThrownBy(() -> ((TCompletableFuture<Integer>) mapped).join())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(mapped.toCompletableFuture().join()).isEqualTo(1);

        TCompletableFuture<String> source = new TCompletableFuture<>();
        TCompletionStage<String> minimal = source.minimalCompletionStage();
        source.complete("later");
        assertThat(minimal.toCompletableFuture().join()).isEqualTo("later");
        assertThat(minimal.toString()).contains("Completed normally");
    }

    // --- Chains ---------------------------------------------------------------------------------

    @Test
    void chainsRegisteredBeforeCompletionRunInTheCompletingThread() throws Exception {
        TCompletableFuture<Integer> source = new TCompletableFuture<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        TCompletableFuture<String> chained = source.thenApply(v -> {
                    ranOn.set(Thread.currentThread());
                    return v + 1;
                })
                .thenCompose(v -> TCompletableFuture.completedFuture(v * 10))
                .thenCombine(TCompletableFuture.completedFuture("!"), (v, s) -> v + s)
                .handle((v, x) -> x == null ? v : "failed")
                .whenComplete((v, x) -> {});
        assertThat(source.getNumberOfDependents()).isEqualTo(1);
        assertThat(chained.isDone()).isFalse();

        Thread completer = new Thread(() -> source.complete(1));
        completer.start();
        completer.join();

        assertThat(chained.join()).isEqualTo("20!");
        assertThat(ranOn.get()).isSameAs(completer);
    }

    @Test
    void chainsRegisteredAfterCompletionRunInTheCallingThread() {
        TCompletableFuture<Integer> source = TCompletableFuture.completedFuture(1);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        TCompletableFuture<Integer> chained = source.thenApply(v -> {
            ranOn.set(Thread.currentThread());
            return v + 1;
        });
        assertThat(chained.isDone()).isTrue();
        assertThat(chained.join()).isEqualTo(2);
        assertThat(ranOn.get()).isSameAs(Thread.currentThread());
    }

    @Test
    void acceptRunAndBothVariants() {
        List<String> seen = new ArrayList<>();
        TCompletableFuture<String> a = new TCompletableFuture<>();
        TCompletableFuture<String> b = new TCompletableFuture<>();
        TCompletableFuture<Void> accepted = a.thenAccept(v -> seen.add("accept " + v));
        TCompletableFuture<Void> ran = a.thenRun(() -> seen.add("run"));
        TCompletableFuture<Void> both = a.thenAcceptBoth(b, (x, y) -> seen.add("both " + x + y));
        TCompletableFuture<Void> afterBoth = a.runAfterBoth(b, () -> seen.add("after both"));

        a.complete("a");
        assertThat(accepted.join()).isNull();
        assertThat(ran.isDone()).isTrue();
        assertThat(both.isDone()).isFalse();
        assertThat(afterBoth.isDone()).isFalse();

        b.complete("b");
        assertThat(both.join()).isNull();
        assertThat(afterBoth.join()).isNull();
        assertThat(seen).containsExactly("accept a", "run", "both ab", "after both");
    }

    @Test
    void eitherVariantsTakeTheFirstAndRunOnce() {
        TCompletableFuture<String> a = new TCompletableFuture<>();
        TCompletableFuture<String> b = new TCompletableFuture<>();
        AtomicInteger runs = new AtomicInteger();
        TCompletableFuture<String> applied = a.applyToEither(b, v -> {
            runs.incrementAndGet();
            return v + "!";
        });
        TCompletableFuture<Void> accepted = a.acceptEither(b, v -> runs.incrementAndGet());
        TCompletableFuture<Void> ran = a.runAfterEither(b, runs::incrementAndGet);

        b.complete("b");
        a.complete("a");
        assertThat(applied.join()).isEqualTo("b!");
        assertThat(accepted.join()).isNull();
        assertThat(ran.join()).isNull();
        assertThat(runs.get()).isEqualTo(3);
    }

    @Test
    void failuresPropagateWrappedOnce() {
        IllegalStateException boom = new IllegalStateException("boom");
        TCompletableFuture<Integer> source = new TCompletableFuture<>();
        TCompletableFuture<Integer> first = source.thenApply(v -> v + 1);
        TCompletableFuture<Integer> second = first.thenApply(v -> v + 1);
        AtomicReference<Throwable> handled = new AtomicReference<>();
        TCompletableFuture<String> recovered = second.handle((v, x) -> {
            handled.set(x);
            return "recovered";
        });
        source.completeExceptionally(boom);

        assertThat(first.exceptionNow()).isSameAs(boom);
        assertThatThrownBy(first::join).isInstanceOf(TCompletionException.class).hasCause(boom);
        // The wrapper is reused down the chain, not wrapped again.
        assertThatThrownBy(second::join)
                .isInstanceOf(TCompletionException.class)
                .hasCause(boom);
        assertThat(handled.get()).isInstanceOf(TCompletionException.class).hasCause(boom);
        assertThat(recovered.join()).isEqualTo("recovered");
    }

    @Test
    void aThrowingFunctionFailsItsStage() {
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        TCompletableFuture<Integer> failed = TCompletableFuture.completedFuture(1)
                .thenApply(v -> {
                    throw boom;
                });
        assertThatThrownBy(failed::join)
                .isInstanceOf(TCompletionException.class)
                .hasCause(boom);
        assertThatThrownBy(failed::get).isInstanceOf(ExecutionException.class).hasCause(boom);

        TCompletableFuture<Integer> nullCompose =
                TCompletableFuture.completedFuture(1).thenCompose(v -> null);
        assertThatThrownBy(nullCompose::join).hasCauseInstanceOf(NullPointerException.class);
    }

    @Test
    void exceptionallyRecoversOnlyFailures() {
        RuntimeException boom = new RuntimeException("boom");
        AtomicReference<Throwable> passed = new AtomicReference<>();
        assertThat(TCompletableFuture.<String>failedFuture(boom)
                        .exceptionally(x -> {
                            passed.set(x);
                            return "recovered";
                        })
                        .join())
                .isEqualTo("recovered");
        // The failure the source completed with, not a wrapper.
        assertThat(passed.get()).isSameAs(boom);
        assertThat(TCompletableFuture.completedFuture("fine")
                        .exceptionally(x -> "recovered")
                        .join())
                .isEqualTo("fine");
        assertThat(TCompletableFuture.<String>failedFuture(boom)
                        .exceptionallyCompose(x -> TCompletableFuture.completedFuture("composed"))
                        .join())
                .isEqualTo("composed");

        TCompletableFuture<String> source = new TCompletableFuture<>();
        TCompletableFuture<String> recovered = source.thenApply(v -> v + "?").exceptionally(x -> {
            assertThat(x).isInstanceOf(TCompletionException.class).hasCause(boom);
            return "late";
        });
        source.completeExceptionally(boom);
        assertThat(recovered.join()).isEqualTo("late");
    }

    @Test
    void whenCompletePassesTheResultThrough() {
        RuntimeException boom = new RuntimeException("boom");
        AtomicReference<Object> seen = new AtomicReference<>();
        TCompletableFuture<String> ok = TCompletableFuture.completedFuture("v").whenComplete((v, x) -> seen.set(v));
        assertThat(ok.join()).isEqualTo("v");
        assertThat(seen.get()).isEqualTo("v");

        RuntimeException actionFailure = new RuntimeException("action");
        TCompletableFuture<String> failedAction = TCompletableFuture.completedFuture("v")
                .whenComplete((v, x) -> {
                    throw actionFailure;
                });
        assertThatThrownBy(failedAction::join).hasCause(actionFailure);

        TCompletableFuture<String> bothFailed = TCompletableFuture.<String>failedFuture(boom)
                .whenComplete((v, x) -> {
                    throw actionFailure;
                });
        assertThatThrownBy(bothFailed::join).hasCause(boom);
        assertThat(boom.getSuppressed()).containsExactly(actionFailure);
    }

    @Test
    void longChainsDoNotBreak() {
        TCompletableFuture<Integer> source = new TCompletableFuture<>();
        TCompletableFuture<Integer> tail = source;
        for (int i = 0; i < 1000; i++) {
            tail = tail.thenApply(v -> v + 1);
        }
        source.complete(0);
        assertThat(tail.join()).isEqualTo(1000);
    }

    // --- Combinators ----------------------------------------------------------------------------

    @Test
    void allOfWaitsForAllAndReportsAFailure() {
        TCompletableFuture<Integer> a = new TCompletableFuture<>();
        TCompletableFuture<Integer> b = new TCompletableFuture<>();
        TCompletableFuture<Void> all = TCompletableFuture.allOf(a, b);
        a.complete(1);
        assertThat(all.isDone()).isFalse();
        b.complete(2);
        assertThat(all.join()).isNull();

        assertThat(TCompletableFuture.allOf().join()).isNull();

        RuntimeException boom = new RuntimeException("boom");
        TCompletableFuture<Integer> c = new TCompletableFuture<>();
        TCompletableFuture<Void> failing = TCompletableFuture.allOf(TCompletableFuture.failedFuture(boom), c);
        assertThat(failing.isDone()).isFalse();
        c.complete(3);
        assertThatThrownBy(failing::join)
                .isInstanceOf(TCompletionException.class)
                .hasCause(boom);
    }

    @Test
    void anyOfTakesTheFirst() {
        TCompletableFuture<Integer> a = new TCompletableFuture<>();
        TCompletableFuture<String> b = new TCompletableFuture<>();
        TCompletableFuture<Object> any = TCompletableFuture.anyOf(a, b);
        assertThat(any.isDone()).isFalse();
        b.complete("b");
        a.complete(1);
        assertThat(any.join()).isEqualTo("b");

        assertThat(TCompletableFuture.anyOf().isDone()).isFalse();

        RuntimeException boom = new RuntimeException("boom");
        TCompletableFuture<Object> failed =
                TCompletableFuture.anyOf(TCompletableFuture.failedFuture(boom), new TCompletableFuture<>());
        assertThatThrownBy(failed::join)
                .isInstanceOf(TCompletionException.class)
                .hasCause(boom);
    }

    // --- Async ----------------------------------------------------------------------------------

    @Test
    void asyncVariantsUseTheGivenExecutor() {
        CountingExecutor executor = new CountingExecutor();
        TCompletableFuture<Integer> supplied = TCompletableFuture.supplyAsync(() -> 1, executor);
        AtomicReference<Thread> applyThread = new AtomicReference<>();
        TCompletableFuture<Integer> applied = supplied.thenApplyAsync(
                v -> {
                    applyThread.set(Thread.currentThread());
                    return v + 1;
                },
                executor);
        TCompletableFuture<Integer> composed =
                applied.thenComposeAsync(v -> TCompletableFuture.completedFuture(v * 10), executor);
        TCompletableFuture<Integer> combined =
                composed.thenCombineAsync(TCompletableFuture.completedFuture(5), Integer::sum, executor);
        TCompletableFuture<String> handled = combined.handleAsync((v, x) -> "h" + v, executor);
        TCompletableFuture<String> whenDone = handled.whenCompleteAsync((v, x) -> {}, executor);
        TCompletableFuture<Void> ran = TCompletableFuture.runAsync(() -> {}, executor);

        assertThat(whenDone.join()).isEqualTo("h25");
        assertThat(ran.join()).isNull();
        assertThat(executor.threads.contains(applyThread.get())).isTrue();
        assertThat(executor.tasks.get()).isEqualTo(7);

        TCompletableFuture<String> recovered = TCompletableFuture.<String>failedFuture(new RuntimeException())
                .exceptionallyAsync(x -> "async", executor);
        assertThat(recovered.join()).isEqualTo("async");
        TCompletableFuture<String> composedRecovery = TCompletableFuture.<String>failedFuture(new RuntimeException())
                .exceptionallyComposeAsync(x -> TCompletableFuture.completedFuture("async compose"), executor);
        assertThat(composedRecovery.join()).isEqualTo("async compose");
    }

    @Test
    void asyncVariantsWithoutExecutorUseTheDefault() {
        Thread caller = Thread.currentThread();
        TCompletableFuture<Thread> ranOn = TCompletableFuture.supplyAsync(Thread::currentThread);
        assertThat(ranOn.join()).isNotSameAs(caller);
        assertThat(ranOn.join().isDaemon()).isTrue();

        TCompletableFuture<Thread> appliedOn =
                TCompletableFuture.completedFuture(1).thenApplyAsync(v -> Thread.currentThread());
        assertThat(appliedOn.join()).isNotSameAs(caller);

        AtomicInteger counter = new AtomicInteger();
        TCompletableFuture<Void> ran = TCompletableFuture.runAsync(counter::incrementAndGet);
        assertThat(ran.join()).isNull();
        assertThat(counter.get()).isEqualTo(1);

        TCompletableFuture<String> completed = new TCompletableFuture<String>().completeAsync(() -> "async");
        assertThat(completed.join()).isEqualTo("async");
    }

    @Test
    void aRejectingExecutorFailsTheDependentStage() {
        RuntimeException rejected = new RuntimeException("rejected");
        Executor refusing = task -> {
            throw rejected;
        };
        assertThatThrownBy(() -> TCompletableFuture.supplyAsync(() -> 1, refusing))
                .isSameAs(rejected);
        TCompletableFuture<Integer> dependent =
                TCompletableFuture.completedFuture(1).thenApplyAsync(v -> v, refusing);
        assertThatThrownBy(dependent::join).hasCause(rejected);
    }

    @Test
    void delayedExecutorWaitsBeforeRunning() {
        long start = System.nanoTime();
        TCompletableFuture<Long> ran = TCompletableFuture.supplyAsync(
                System::nanoTime, TCompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
        assertThat(ran.join() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(50));
    }

    // --- Cancel, timeouts, obtrude --------------------------------------------------------------

    @Test
    void cancelCompletesWithCancellationException() {
        TCompletableFuture<String> future = new TCompletableFuture<>();
        TCompletableFuture<String> dependent = future.thenApply(v -> v);
        assertThat(future.cancel(true)).isTrue();
        assertThat(future.cancel(false)).isTrue();
        assertThat(future.isCancelled()).isTrue();
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThat(future.state()).isEqualTo(Future.State.CANCELLED);
        assertThatThrownBy(future::join).isInstanceOf(CancellationException.class);
        assertThatThrownBy(future::get).isInstanceOf(CancellationException.class);
        assertThatThrownBy(future::exceptionNow).isInstanceOf(IllegalStateException.class);

        assertThat(dependent.isCancelled()).isFalse();
        assertThatThrownBy(dependent::join)
                .isInstanceOf(TCompletionException.class)
                .hasCauseInstanceOf(CancellationException.class);

        TCompletableFuture<String> done = TCompletableFuture.completedFuture("v");
        assertThat(done.cancel(true)).isFalse();
    }

    @Test
    void orTimeoutFailsWhenLate() {
        TCompletableFuture<String> late = new TCompletableFuture<String>().orTimeout(30, TimeUnit.MILLISECONDS);
        assertThatThrownBy(late::join)
                .isInstanceOf(TCompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        TCompletableFuture<String> onTime = new TCompletableFuture<String>().orTimeout(10, TimeUnit.SECONDS);
        onTime.complete("in time");
        assertThat(onTime.join()).isEqualTo("in time");
    }

    @Test
    void completeOnTimeoutUsesTheDefault() {
        TCompletableFuture<String> late =
                new TCompletableFuture<String>().completeOnTimeout("default", 30, TimeUnit.MILLISECONDS);
        assertThat(late.join()).isEqualTo("default");

        TCompletableFuture<String> onTime =
                new TCompletableFuture<String>().completeOnTimeout("default", 10, TimeUnit.SECONDS);
        onTime.complete("value");
        assertThat(onTime.join()).isEqualTo("value");
    }

    @Test
    void obtrudeOverwrites() {
        TCompletableFuture<String> future = TCompletableFuture.completedFuture("first");
        future.obtrudeValue("second");
        assertThat(future.join()).isEqualTo("second");
        RuntimeException boom = new RuntimeException();
        future.obtrudeException(boom);
        assertThat(future.exceptionNow()).isSameAs(boom);
    }

    @Test
    void copyAndToString() {
        TCompletableFuture<String> future = new TCompletableFuture<>();
        TCompletableFuture<String> copy = future.copy();
        assertThat(future.toString()).endsWith("[Not completed, 1 dependents]");
        future.complete("v");
        assertThat(copy.join()).isEqualTo("v");
        assertThat(future.toString()).endsWith("[Completed normally]");
        assertThat(TCompletableFuture.failedFuture(new RuntimeException("x")).toString())
                .contains("Completed exceptionally");
        assertThat(future.toCompletableFuture()).isSameAs(future);
    }

    // --- Concurrency ----------------------------------------------------------------------------

    @Test
    void manyThreadsRegisteringAndCompletingConcurrently() throws Exception {
        int threads = 16;
        int perThread = 500;
        TCompletableFuture<Integer> shared = new TCompletableFuture<>();
        AtomicInteger callbacks = new AtomicInteger();
        List<TCompletableFuture<Integer>> dependents = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    dependents.add(shared.thenApply(v -> {
                        callbacks.incrementAndGet();
                        return v + 1;
                    }));
                    if (i == perThread / 2 && shared.complete(id)) {
                        winners.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(winners.get()).isEqualTo(1);
        int value = shared.join();
        assertThat(callbacks.get()).isEqualTo(threads * perThread);
        assertThat(dependents).hasSize(threads * perThread);
        for (TCompletableFuture<Integer> dependent : dependents) {
            assertThat(dependent.getNow(-1)).isEqualTo(value + 1);
        }
        assertThat(shared.getNumberOfDependents()).isZero();
    }

    @Test
    void manyWaitersWakeUp() throws Exception {
        TCompletableFuture<String> future = new TCompletableFuture<>();
        List<TCompletableFuture<String>> seen = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            seen.add(TCompletableFuture.supplyAsync(future::join));
        }
        sleep(20);
        future.complete("go");
        assertThat(TCompletableFuture.allOf(seen.toArray(new TCompletableFuture<?>[0]))
                        .thenApply(v -> seen.stream()
                                .map(TCompletableFuture::join)
                                .distinct()
                                .toList())
                        .get(10, TimeUnit.SECONDS))
                .containsExactly("go");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
