package incari.platform.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The game's background pool in the page: coroutines that run the pool's tasks in the time left
 * over after each frame, never inside one.
 *
 * <p>The page has one thread, so a task run where it's submitted lands mid-frame, and a burst
 * stalls the frame: turning the camera schedules a mesh for every section coming into view, and
 * the section dispatcher resubmits itself after each, so one frame would compile the whole backlog
 * (hundreds of milliseconds). Here a task only queues. Once the game's frame has run and the page
 * has drawn it, a runner runs queued tasks until shortly before the next frame is due, then waits
 * for the next slot ({@link Slots}). A burst spreads over as many frames as it takes.
 *
 * <p>A task runs to its end once started: a slot can run over by one task. A thread that waits on
 * a task still queued here (a {@code join} on the render thread) waits for the next slot, which
 * comes whether or not the game draws, so nothing deadlocks.
 *
 * <p>One runner works at a time (it holds the pool). A task that waits for work done elsewhere (a
 * Web Worker's mesh) steps aside ({@link #awaitOutside}): another runner carries on with the queue
 * in the same slot, and the waiting task takes the pool back in a later slot. So several meshes are
 * in flight at once, and everything still runs between frames.
 *
 * <p>A task that blocks in any other wait (a {@code join} on another pool task, a lock held by the
 * integrated server) gives the pool up for good ({@link #beforeWait}): what it waits for may be
 * queued behind it, and would never run while it held the pool (world generation's structure
 * placement joins work it queued itself). Another runner takes the queue; the waiting task goes on
 * when woken, outside the slots, and its runner then queues for the pool like any other.
 *
 * <p>Everything here runs on the page's one thread; the monitor only orders the runners, which are
 * coroutines that suspend in {@link Slots#await}, in a monitor wait, or in an outside wait.
 */
final class IdleTimeExecutor extends AbstractExecutorService {

    /** When the runners may run: the page's frames ({@link Dom}), or a test's. */
    interface Slots {

        /** Asks for the next slot: work is queued. Never suspends; asking twice asks once. */
        void arm();

        /**
         * Suspends until the next slot once one is asked for ({@link #arm}), before or during the
         * wait.
         *
         * @return when the slot ends, on {@link #now}'s clock
         */
        double await();

        /** The page's clock, in milliseconds. */
        double now();

        /**
         * A stretch of the pool's work just ended (a task, or a part of one before it paused or
         * waited outside): how long it ran, and how far past its slot's deadline it ended (negative
         * if in time). For the frame table's pool columns; wherever the page resumed the work from.
         */
        default void worked(double millis, double pastDeadline) {}
    }

    /**
     * The most runners, not counting those that gave the pool up ({@link #beforeWait}). Runners are
     * cheap coroutines, but the meshes they wait on aren't.
     */
    private static final int MAX_RUNNERS = 16;

    /** The pool the current thread is a runner of, for {@link #awaitOutside}. */
    private static final ThreadLocal<@Nullable IdleTimeExecutor> CURRENT = new ThreadLocal<>();
    /** The current thread's runner, if it is one. */
    private static final ThreadLocal<@Nullable Runner> RUNNER = new ThreadLocal<>();

    /** A runner's own state. */
    private static final class Runner {
        /** Its task gave the pool up to wait ({@link #beforeWait}); it finishes outside the slots. */
        boolean gaveUp;
    }

    /** Every pool made, for {@link #queuedEverywhere}: a few, made at start-up and kept. */
    private static final List<IdleTimeExecutor> POOLS = new ArrayList<>();

    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final Slots slots;
    private final Consumer<Runnable> startRunner;
    private int runners;
    /** Runners parked, waiting for work or for the pool. */
    private int parked;
    /** Runners in an outside wait ({@link #stepAside}), not yet back. */
    private int outside;
    /** Tasks back from an outside wait, waiting to take the pool back. */
    private int rejoining;
    /** Runners whose task gave the pool up to wait ({@link #beforeWait}), not back in their loop. */
    private int waiting;
    /** The runner holding the pool, or null. */
    private @Nullable Runner holder;
    /** When the current slot ends. */
    private double deadline = Double.NEGATIVE_INFINITY;
    /** A slot just began: its first task runs even if the slot is already over. */
    private boolean freshSlot;
    /** When the pool's current stretch of work began, for {@link Slots#worked}. */
    private double stretchStart;

    private boolean shutdown;
    private boolean terminated;

    /**
     * @param startRunner starts {@code work} on a thread of its own: the first time a task comes,
     *     and whenever a task steps aside with work still queued and no runner free
     */
    IdleTimeExecutor(Slots slots, Consumer<Runnable> startRunner) {
        this.slots = slots;
        this.startRunner = startRunner;
        synchronized (POOLS) {
            POOLS.add(this);
        }
    }

    /** How many tasks wait for a slot in all the pools of this program. */
    static int queuedEverywhere() {
        int total = 0;
        synchronized (POOLS) {
            for (IdleTimeExecutor pool : POOLS) {
                total += pool.queued();
            }
        }
        return total;
    }

    /** The executor the page's game uses: its runners are daemon threads named {@code threadName}-n. */
    static IdleTimeExecutor forPage(String threadName) {
        int[] count = {0};
        return new IdleTimeExecutor(new Dom.IdleSlots(), work -> {
            Thread thread = new Thread(work, threadName + "-" + ++count[0]);
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * The executor in a copy of the game in a worker (ADR 0039): slots are turns of the worker's
     * event loop ({@link WorkerSlots}), as it has no frames. Its runners are daemon threads named
     * {@code threadName}-n.
     */
    static IdleTimeExecutor forWorker(String threadName) {
        int[] count = {0};
        return new IdleTimeExecutor(new WorkerSlots(), work -> {
            Thread thread = new Thread(work, threadName + "-" + ++count[0]);
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * Waits for something done outside the page's thread, from a task of the pool this thread runs:
     * the pool goes on with other tasks meanwhile, and this task goes on in a later slot. Anywhere
     * else, it just waits.
     */
    static <T> T awaitOutside(Supplier<T> wait) {
        IdleTimeExecutor pool = CURRENT.get();
        return pool == null ? wait.get() : pool.stepAside(wait);
    }

    /**
     * From a task of the pool this thread runs: if the slot is over, waits for the next one, keeping
     * the pool, and goes on there. A long task (a section's snapshot) calls it between its parts so
     * it never runs far past its slot. Anywhere else it returns at once.
     */
    static void pauseIfLate() {
        IdleTimeExecutor pool = CURRENT.get();
        if (pool != null) {
            pool.pauseIfLateHere();
        }
    }

    /**
     * Called just before any wait on a thread of the page (web-classlib's {@code Waits}, wired by
     * the entry point). A runner holding the pool gives it up, for good: see the class comment.
     * Anywhere else, and in the pool's own waits, it does nothing.
     */
    static void beforeWait() {
        IdleTimeExecutor pool = CURRENT.get();
        Runner runner = RUNNER.get();
        if (pool != null && runner != null) {
            pool.giveUp(runner);
        }
    }

    private void giveUp(Runner runner) {
        synchronized (this) {
            if (holder != runner) {
                return;
            }
            holder = null;
            runner.gaveUp = true;
            waiting++;
            if (!queue.isEmpty() && parked == 0 && runners - outside - waiting == 0) {
                startRunnerLocked();
            }
            notifyAll();
        }
        endStretch();
    }

    /** Whether the current thread is the runner holding the pool. */
    private synchronized boolean holdsPool() {
        Runner runner = RUNNER.get();
        return runner != null && holder == runner;
    }

    private void pauseIfLateHere() {
        if (!holdsPool()) {
            // Its task gave the pool up to wait: it has no slot to pause in.
            return;
        }
        if (slots.now() >= deadline) {
            endStretch();
            slots.arm();
            deadline = slots.await();
            freshSlot = false;
            stretchStart = slots.now();
        }
    }

    private void endStretch() {
        double now = slots.now();
        slots.worked(now - stretchStart, now - deadline);
        stretchStart = now;
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (this) {
            if (shutdown) {
                throw new RejectedExecutionException("The background pool is shut down");
            }
            queue.add(task);
            if (runners == 0) {
                startRunnerLocked();
            } else if (parked > 0) {
                notifyAll();
            } else if (outside + waiting == runners && runners - waiting < MAX_RUNNERS) {
                // Every runner is waiting (a mesh in a worker, or a task that gave the pool up):
                // one more takes the queue, rather than the task waiting for an answer.
                startRunnerLocked();
            }
        }
        slots.arm();
    }

    private void startRunnerLocked() {
        runners++;
        startRunner.accept(this::work);
    }

    /** A runner's loop: take the pool when there's work and it's free, run slots, give it back. */
    private void work() {
        CURRENT.set(this);
        Runner me = new Runner();
        RUNNER.set(me);
        while (true) {
            synchronized (this) {
                parked++;
                boolean interrupted = false;
                while (!(shutdown && queue.isEmpty()) && (holder != null || queue.isEmpty() || rejoining > 0)) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                parked--;
                if (shutdown && queue.isEmpty()) {
                    runners--;
                    if (runners == 0) {
                        terminated = true;
                    }
                    notifyAll();
                    return;
                }
                holder = me;
            }
            stretchStart = slots.now();
            try {
                runSlots(me);
            } finally {
                synchronized (this) {
                    if (holder == me) {
                        holder = null;
                    }
                    if (me.gaveUp) {
                        me.gaveUp = false;
                        waiting--;
                    }
                    notifyAll();
                }
            }
        }
    }

    /**
     * Runs queued tasks in slots while this runner holds the pool, until the queue is empty or a
     * task back from an outside wait wants the pool, or a task gave it up to wait.
     */
    private void runSlots(Runner me) {
        while (true) {
            Runnable task;
            synchronized (this) {
                if (queue.isEmpty() || rejoining > 0) {
                    return;
                }
            }
            if (!freshSlot && slots.now() >= deadline) {
                slots.arm();
                deadline = slots.await();
                freshSlot = true;
                stretchStart = slots.now();
                continue;
            }
            synchronized (this) {
                task = queue.poll();
            }
            if (task != null) {
                freshSlot = false;
                run(task);
                if (me.gaveUp) {
                    // It finished outside the slots; the runner queues for the pool again.
                    return;
                }
                endStretch();
            }
        }
    }

    /** Gives the pool up for {@code wait}, then takes it back, in a slot. */
    private <T> T stepAside(Supplier<T> wait) {
        if (!holdsPool()) {
            // Its task already gave the pool up to wait: nothing to step aside from.
            return wait.get();
        }
        endStretch();
        Runner me;
        synchronized (this) {
            me = Objects.requireNonNull(holder);
            holder = null;
            outside++;
            if (!queue.isEmpty()) {
                if (parked > 0) {
                    notifyAll();
                } else if (runners - waiting < MAX_RUNNERS) {
                    startRunnerLocked();
                }
            }
        }
        try {
            return wait.get();
        } finally {
            synchronized (this) {
                outside--;
                rejoining++;
                boolean interrupted = false;
                while (holder != null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                rejoining--;
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                holder = me;
            }
            stretchStart = slots.now();
            pauseIfLateHere();
        }
    }

    /**
     * Runs queued tasks, oldest first, until {@code deadline} has passed or none are left; tasks
     * queued meanwhile count. At least one runs if any are queued, so the pool always moves. (The
     * tests' way of running a slot; the runners run the same way through {@link #runSlots}.)
     *
     * @return how many ran
     */
    int runUntil(double deadline) {
        int ran = 0;
        Runnable task;
        while ((task = poll()) != null) {
            run(task);
            ran++;
            if (slots.now() >= deadline) {
                break;
            }
        }
        return ran;
    }

    private synchronized @Nullable Runnable poll() {
        return queue.poll();
    }

    /** Runs one task; what it throws goes where an uncaught exception in a pool thread would. */
    private static void run(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException | Error e) {
            Thread thread = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
            if (handler == null) {
                handler = Thread.getDefaultUncaughtExceptionHandler();
            }
            if (handler != null) {
                handler.uncaughtException(thread, e);
            } else {
                e.printStackTrace();
            }
        }
    }

    /** How many tasks wait for a slot. */
    synchronized int queued() {
        return queue.size();
    }

    /** How many runners have been started. */
    synchronized int runners() {
        return runners;
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            shutdown = true;
            if (runners == 0) {
                terminated = true;
                return;
            }
            notifyAll();
        }
        // Wakes the runners to finish the queue and stop.
        slots.arm();
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        synchronized (this) {
            List<Runnable> dropped = List.copyOf(queue);
            queue.clear();
            notifyAll();
            return dropped;
        }
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return terminated;
    }

    /**
     * Says whether the pool has finished, without waiting: a wait here would hold the page's only
     * thread, and the page never shuts down in an orderly way (the tab just closes).
     */
    @Override
    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) {
        return terminated;
    }
}
