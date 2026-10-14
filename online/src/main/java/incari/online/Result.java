package incari.online;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a request to the service came to: a value, or why not.
 *
 * @param <T> the value's type ({@link Void} for requests with nothing to give back)
 */
public final class Result<T> {

    private final @Nullable T value;
    private final @Nullable Failure failure;

    private Result(@Nullable T value, @Nullable Failure failure) {
        this.value = value;
        this.failure = failure;
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(Objects.requireNonNull(value), null);
    }

    /** Success with nothing to give back. */
    public static Result<Void> done() {
        return new Result<>(null, null);
    }

    public static <T> Result<T> failed(Failure failure) {
        return new Result<>(null, failure);
    }

    public boolean ok() {
        return failure == null;
    }

    /** The value; throws if it failed or has none. */
    public T value() {
        if (value == null) {
            throw new IllegalStateException("No value: " + failure);
        }
        return value;
    }

    /** Why it failed; null if it didn't. */
    public @Nullable Failure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return failure == null ? "ok" : failure.name();
    }
}
