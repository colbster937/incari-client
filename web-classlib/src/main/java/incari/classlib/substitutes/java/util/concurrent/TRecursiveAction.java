package incari.classlib.substitutes.java.util.concurrent;

import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.RecursiveAction}: a {@link TForkJoinTask} with no result. */
public abstract class TRecursiveAction extends TForkJoinTask<@Nullable Void> {

    public TRecursiveAction() {}

    /** The task's work. */
    protected abstract void compute();

    /** Always {@code null}. */
    @Override
    public final @Nullable Void getRawResult() {
        return null;
    }

    @Override
    protected final void setRawResult(@Nullable Void mustBeNull) {}

    @Override
    protected final boolean exec() {
        compute();
        return true;
    }
}
