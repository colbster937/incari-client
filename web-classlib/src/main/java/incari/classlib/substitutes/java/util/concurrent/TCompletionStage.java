package incari.classlib.substitutes.java.util.concurrent;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.CompletionStage}: one step of an asynchronous computation that runs
 * actions when it completes. TeaVM doesn't ship it; {@link TCompletableFuture} is the implementation
 * the game uses. In the browser the {@code *Async} variants run on coroutines, not pool threads.
 *
 * @param <T> the stage's result type
 */
public interface TCompletionStage<T extends @Nullable Object> {

    <U extends @Nullable Object> TCompletionStage<U> thenApply(Function<? super T, ? extends U> fn);

    <U extends @Nullable Object> TCompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn);

    <U extends @Nullable Object> TCompletionStage<U> thenApplyAsync(
            Function<? super T, ? extends U> fn, Executor executor);

    TCompletionStage<Void> thenAccept(Consumer<? super T> action);

    TCompletionStage<Void> thenAcceptAsync(Consumer<? super T> action);

    TCompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor);

    TCompletionStage<Void> thenRun(Runnable action);

    TCompletionStage<Void> thenRunAsync(Runnable action);

    TCompletionStage<Void> thenRunAsync(Runnable action, Executor executor);

    <U extends @Nullable Object, V extends @Nullable Object> TCompletionStage<V> thenCombine(
            TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn);

    <U extends @Nullable Object, V extends @Nullable Object> TCompletionStage<V> thenCombineAsync(
            TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn);

    <U extends @Nullable Object, V extends @Nullable Object> TCompletionStage<V> thenCombineAsync(
            TCompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor);

    <U extends @Nullable Object> TCompletionStage<Void> thenAcceptBoth(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action);

    <U extends @Nullable Object> TCompletionStage<Void> thenAcceptBothAsync(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action);

    <U extends @Nullable Object> TCompletionStage<Void> thenAcceptBothAsync(
            TCompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor);

    TCompletionStage<Void> runAfterBoth(TCompletionStage<?> other, Runnable action);

    TCompletionStage<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action);

    TCompletionStage<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action, Executor executor);

    <U extends @Nullable Object> TCompletionStage<U> applyToEither(
            TCompletionStage<? extends T> other, Function<? super T, U> fn);

    <U extends @Nullable Object> TCompletionStage<U> applyToEitherAsync(
            TCompletionStage<? extends T> other, Function<? super T, U> fn);

    <U extends @Nullable Object> TCompletionStage<U> applyToEitherAsync(
            TCompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor);

    TCompletionStage<Void> acceptEither(TCompletionStage<? extends T> other, Consumer<? super T> action);

    TCompletionStage<Void> acceptEitherAsync(TCompletionStage<? extends T> other, Consumer<? super T> action);

    TCompletionStage<Void> acceptEitherAsync(
            TCompletionStage<? extends T> other, Consumer<? super T> action, Executor executor);

    TCompletionStage<Void> runAfterEither(TCompletionStage<?> other, Runnable action);

    TCompletionStage<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action);

    TCompletionStage<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action, Executor executor);

    <U extends @Nullable Object> TCompletionStage<U> thenCompose(Function<? super T, ? extends TCompletionStage<U>> fn);

    <U extends @Nullable Object> TCompletionStage<U> thenComposeAsync(
            Function<? super T, ? extends TCompletionStage<U>> fn);

    <U extends @Nullable Object> TCompletionStage<U> thenComposeAsync(
            Function<? super T, ? extends TCompletionStage<U>> fn, Executor executor);

    <U extends @Nullable Object> TCompletionStage<U> handle(BiFunction<? super T, @Nullable Throwable, ? extends U> fn);

    <U extends @Nullable Object> TCompletionStage<U> handleAsync(
            BiFunction<? super T, @Nullable Throwable, ? extends U> fn);

    <U extends @Nullable Object> TCompletionStage<U> handleAsync(
            BiFunction<? super T, @Nullable Throwable, ? extends U> fn, Executor executor);

    TCompletionStage<T> whenComplete(BiConsumer<? super T, ? super @Nullable Throwable> action);

    TCompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super @Nullable Throwable> action);

    TCompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super @Nullable Throwable> action, Executor executor);

    TCompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn);

    // The defaults below serve other implementations of this interface; TCompletableFuture overrides
    // them all. Each routes the failure through handle(...) and flattens the resulting stage.

    default TCompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
        return handle((value, failure) ->
                        failure == null ? this : this.<T>handleAsync((v, x) -> fn.apply(Objects.requireNonNull(x))))
                .thenCompose(Function.identity());
    }

    default TCompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
        return handle((value, failure) -> failure == null
                        ? this
                        : this.<T>handleAsync((v, x) -> fn.apply(Objects.requireNonNull(x)), executor))
                .thenCompose(Function.identity());
    }

    default TCompletionStage<T> exceptionallyCompose(Function<Throwable, ? extends TCompletionStage<T>> fn) {
        return handle((value, failure) -> failure == null ? this : fn.apply(failure))
                .thenCompose(Function.identity());
    }

    default TCompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn) {
        return handle((value, failure) -> failure == null
                        ? this
                        : this.<TCompletionStage<T>>handleAsync((v, x) -> fn.apply(Objects.requireNonNull(x)))
                                .thenCompose(Function.identity()))
                .thenCompose(Function.identity());
    }

    default TCompletionStage<T> exceptionallyComposeAsync(
            Function<Throwable, ? extends TCompletionStage<T>> fn, Executor executor) {
        return handle((value, failure) -> failure == null
                        ? this
                        : this.<TCompletionStage<T>>handleAsync((v, x) -> fn.apply(Objects.requireNonNull(x)), executor)
                                .thenCompose(Function.identity()))
                .thenCompose(Function.identity());
    }

    TCompletableFuture<T> toCompletableFuture();
}
