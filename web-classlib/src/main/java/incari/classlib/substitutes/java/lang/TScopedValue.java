package incari.classlib.substitutes.java.lang;

import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.lang.ScopedValue} (Java 25), which TeaVM lacks: a value bound for the extent of a
 * call on one thread. Bindings are an immutable list per thread; a {@link Carrier} pushes its
 * bindings for the call and restores the previous ones after.
 */
public final class TScopedValue<T extends @Nullable Object> {

    private static final ThreadLocal<@Nullable Binding> BINDINGS = new ThreadLocal<>();

    private TScopedValue() {}

    public static <T extends @Nullable Object> TScopedValue<T> newInstance() {
        return new TScopedValue<>();
    }

    public static <T extends @Nullable Object> Carrier where(TScopedValue<T> key, T value) {
        return new Carrier(new Binding(key, value, null));
    }

    public T get() {
        Binding binding = find(this);
        if (binding == null) {
            throw new NoSuchElementException("ScopedValue not bound");
        }
        return binding.valueOf(this);
    }

    public boolean isBound() {
        return find(this) != null;
    }

    public T orElse(T other) {
        Binding binding = find(this);
        return binding == null ? other : binding.valueOf(this);
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Binding binding = find(this);
        if (binding == null) {
            throw exceptionSupplier.get();
        }
        return binding.valueOf(this);
    }

    @Override
    public String toString() {
        return "ScopedValue@" + Integer.toHexString(hashCode());
    }

    private static @Nullable Binding find(TScopedValue<?> key) {
        for (Binding binding = BINDINGS.get(); binding != null; binding = binding.next) {
            if (binding.key == key) {
                return binding;
            }
        }
        return null;
    }

    /** An operation run with bindings in effect, returning a result. */
    @FunctionalInterface
    public interface CallableOp<T extends @Nullable Object, X extends Throwable> {
        T call() throws X;
    }

    /** Bindings waiting to be put in effect for a call. */
    public static final class Carrier {
        private final Binding bindings;

        private Carrier(Binding bindings) {
            this.bindings = bindings;
        }

        public <T extends @Nullable Object> Carrier where(TScopedValue<T> key, T value) {
            return new Carrier(new Binding(key, value, bindings));
        }

        public <T extends @Nullable Object> T get(TScopedValue<T> key) {
            for (Binding binding = bindings; binding != null; binding = binding.next) {
                if (binding.key == key) {
                    return binding.valueOf(key);
                }
            }
            throw new NoSuchElementException("key not bound");
        }

        public <R extends @Nullable Object, X extends Throwable> R call(CallableOp<? extends R, X> op) throws X {
            @Nullable Binding previous = BINDINGS.get();
            BINDINGS.set(stack(previous));
            try {
                return op.call();
            } finally {
                BINDINGS.set(previous);
            }
        }

        public void run(Runnable op) {
            @Nullable Binding previous = BINDINGS.get();
            BINDINGS.set(stack(previous));
            try {
                op.run();
            } finally {
                BINDINGS.set(previous);
            }
        }

        // This carrier's bindings on top of the ones already in effect; the newest wins lookups.
        private Binding stack(@Nullable Binding below) {
            Binding result = below;
            java.util.ArrayList<Binding> mine = new java.util.ArrayList<>();
            for (Binding binding = bindings; binding != null; binding = binding.next) {
                mine.add(binding);
            }
            for (int i = mine.size() - 1; i >= 0; i--) {
                result = new Binding(mine.get(i).key, mine.get(i).value, result);
            }
            return java.util.Objects.requireNonNull(result);
        }
    }

    private static final class Binding {
        final TScopedValue<?> key;
        final @Nullable Object value;
        final @Nullable Binding next;

        Binding(TScopedValue<?> key, @Nullable Object value, @Nullable Binding next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }

        // Only where(key, value) makes bindings, so the value is the key's T, and null only when
        // T allows it; neither the compiler nor NullAway can see that link.
        @SuppressWarnings({"unchecked", "NullAway"})
        <V extends @Nullable Object> V valueOf(TScopedValue<V> owner) {
            if (owner != key) {
                throw new IllegalArgumentException("not this binding's key");
            }
            return (V) value;
        }
    }
}
