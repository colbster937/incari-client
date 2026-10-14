package incari.classlib.substitutes.java.util.concurrent;

import org.jspecify.annotations.Nullable;

/** {@code java.util.concurrent.ThreadFactory}. In the browser a new thread is a new coroutine. */
public interface TThreadFactory {

    /** A new, unstarted thread that runs {@code r}, or {@code null} if the factory refuses. */
    @Nullable
    Thread newThread(Runnable r);
}
