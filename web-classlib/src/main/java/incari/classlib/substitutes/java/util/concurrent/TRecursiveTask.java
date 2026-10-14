package incari.classlib.substitutes.java.util.concurrent;

import incari.classlib.concurrent.Monitors;
import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.RecursiveTask}: a {@link TForkJoinTask} that computes a result. */
public abstract class TRecursiveTask<V extends @Nullable Object> extends TForkJoinTask<V> {

    private @Nullable Object result;

    public TRecursiveTask() {}

    /** The task's work. */
    protected abstract V compute();

    @Override
    public final V getRawResult() {
        return Monitors.cast(result);
    }

    @Override
    protected final void setRawResult(V value) {
        result = value;
    }

    @Override
    protected final boolean exec() {
        result = compute();
        return true;
    }
}
