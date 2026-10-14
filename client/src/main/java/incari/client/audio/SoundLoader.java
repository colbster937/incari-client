package incari.client.audio;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * A few threads of its own that read and decode sounds for the platform's decoder. They aren't
 * the game's I/O pool because in the browser that pool runs tasks inline on the game's thread (the
 * platform reports no background threads), and decoding waits for the browser: a wait there
 * would stall the frame. Here it suspends only this thread's coroutine.
 */
final class SoundLoader implements Executor {

    /** Enough that a long file (a track of music) being fetched doesn't hold up short ones. */
    private static final int THREADS = 3;

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean started;

    @Override
    public void execute(Runnable task) {
        synchronized (tasks) {
            if (!started) {
                started = true;
                for (int i = 0; i < THREADS; i++) {
                    Thread thread = new Thread(this::run, "Sound loader " + (i + 1));
                    thread.setDaemon(true);
                    thread.start();
                }
            }
            tasks.addLast(task);
            tasks.notifyAll();
        }
    }

    private void run() {
        while (true) {
            Runnable task;
            synchronized (tasks) {
                while (tasks.isEmpty()) {
                    try {
                        tasks.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                task = tasks.removeFirst();
            }
            // Tasks complete their own futures, failures included (CompletableFuture.supplyAsync).
            task.run();
        }
    }
}
