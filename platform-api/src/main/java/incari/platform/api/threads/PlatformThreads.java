package incari.platform.api.threads;

import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * How the game's background work runs. On a normal JVM the game keeps its own thread pools. In
 * the browser, TeaVM runs every Java thread as a coroutine on the page's one thread, so extra
 * pool threads only add switching cost; real parallelism comes from Web Workers.
 */
public interface PlatformThreads {

    /**
     * How many threads the game's main background pool gets, when the platform doesn't run the
     * pool itself ({@link #backgroundExecutor}).
     *
     * @param suggested what the game would choose on a normal JVM (usually cores - 1)
     * @return the thread count; 0 runs background tasks directly on the thread that submits them
     */
    int backgroundThreads(int suggested);

    /**
     * The platform's own executor for the game's background pool, or null to let the game build
     * the pool from {@link #backgroundThreads} threads. The browser runs the pool's tasks in the
     * time left over after each frame, so a burst of work (terrain meshes as the camera turns)
     * spreads across frames instead of stalling one.
     *
     * @param name the pool's name, for its threads' names ("Main" for the game's worker pool)
     */
    default @Nullable ExecutorService backgroundExecutor(String name) {
        return null;
    }

    /**
     * Starts workers running {@code program} beside the game: {@code "mesher"}, Incari's
     * terrain mesher. Returns null where the platform has none; the game then does the work on its
     * own threads (the desktop, whose background pool is already parallel).
     */
    default @Nullable WorkerPool workers(String program) {
        return null;
    }

    /**
     * Called now and then by a long background task (a section's snapshot). Where the pool runs
     * between frames and the task's time is up, waits for the pool's next turn, so the task spreads
     * over frames instead of holding up the next one; otherwise returns at once. The task must not
     * hold anything the frame needs meanwhile.
     */
    default void pauseIfLate() {}

    /**
     * Whether all the game's threads share one real thread, switching only where they wait (the
     * browser's coroutines). The game's checks for two threads writing one chunk can then never
     * fire, and are skipped (ADR 0030).
     */
    default boolean oneThread() {
        return false;
    }

    /**
     * How many tasks wait in the platform's background pools ({@link #backgroundExecutor}) for
     * their turn: work that can wait for a quiet moment (ADR 0041) asks first. 0 where the platform
     * can't say, or has no pools of its own.
     */
    default int backgroundQueued() {
        return 0;
    }

    /**
     * Starts another copy of the game (a Web Worker in the browser) in the given role and returns
     * the channel to it; null where the platform can't (the desktop runs singleplayer's server on
     * its own thread). ADR 0039.
     *
     * @param role what the copy is for ({@code "server"}); its entry point picks what to run by it
     */
    default @Nullable PlatformChannel startGameWorker(String role) {
        return null;
    }

    /** In a copy started by {@link #startGameWorker}, the channel to the program that started it; null elsewhere. */
    default @Nullable PlatformChannel parentChannel() {
        return null;
    }
}
