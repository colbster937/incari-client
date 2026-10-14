/**
 * Checks that Java threads behave the same in the Fast build (fibers on JSPI, {@code -Pteavm.fast})
 * as with TeaVM's coroutines in the Compatible build: sleeping, waiting and notifying, joining,
 * exceptions thrown after a thread resumed and caught across the frames that waited, and
 * {@code Thread.currentThread()} after each wait. Times a wait/notify ping-pong between two threads, the cost of a thread switch. Prints
 * "probe passed" or "probe failed: ...". Run it in both builds.
 */
public final class JspiProbe {

    private static final int PING_PONGS = 2000;

    static final Object LOCK = new Object();
    static int turn;
    static volatile String failure;

    static void check(boolean condition, String what) {
        if (!condition && failure == null) {
            failure = what;
        }
    }

    static int sleepThenThrow(int depth) throws InterruptedException {
        if (depth == 0) {
            Thread.sleep(1);
            throw new IllegalStateException("after the sleep");
        }
        return sleepThenThrow(depth - 1) + 1;
    }

    public static void main(String[] args) throws InterruptedException {
        Thread main = Thread.currentThread();

        long t0 = System.currentTimeMillis();
        Thread.sleep(20);
        long slept = System.currentTimeMillis() - t0;
        check(slept >= 15, "slept only " + slept + " ms");
        check(Thread.currentThread() == main, "the main thread changed over a sleep");

        try {
            sleepThenThrow(20);
            check(false, "no exception after the sleep");
        } catch (IllegalStateException e) {
            check("after the sleep".equals(e.getMessage()), "wrong exception: " + e);
        }
        check(Thread.currentThread() == main, "the main thread changed over a throw");

        // Three threads, each sleeping and counting, joined.
        int[] counts = new int[3];
        Thread[] workers = new Thread[3];
        for (int i = 0; i < 3; i++) {
            int index = i;
            workers[i] = new Thread(() -> {
                Thread self = Thread.currentThread();
                for (int n = 0; n < 5; n++) {
                    try {
                        Thread.sleep(1 + index);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    check(Thread.currentThread() == self, "worker " + index + " resumed as another thread");
                    counts[index]++;
                }
            }, "worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        check(Thread.currentThread() == main, "the main thread changed over joins");
        for (int i = 0; i < 3; i++) {
            check(counts[i] == 5, "worker " + i + " counted " + counts[i]);
        }

        // Ping-pong: two threads hand a turn back and forth through wait/notify.
        Thread pong = new Thread(() -> {
            for (int n = 0; n < PING_PONGS; n++) {
                synchronized (LOCK) {
                    while (turn != 1) {
                        try {
                            LOCK.wait();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    turn = 0;
                    LOCK.notifyAll();
                }
            }
        }, "pong");
        pong.start();
        long p0 = System.nanoTime();
        for (int n = 0; n < PING_PONGS; n++) {
            synchronized (LOCK) {
                turn = 1;
                LOCK.notifyAll();
                while (turn != 0) {
                    LOCK.wait();
                }
            }
        }
        long p1 = System.nanoTime();
        pong.join();
        check(Thread.currentThread() == main, "the main thread changed over the ping-pong");
        System.out.println("ping-pong: " + String.format("%.1f", (p1 - p0) / 1000.0 / PING_PONGS / 2)
                + " us a switch over " + PING_PONGS + " round trips");

        // An uncaught exception in a thread goes to its handler, and the program goes on.
        Throwable[] uncaught = new Throwable[1];
        Thread thrower = new Thread(() -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            throw new IllegalArgumentException("uncaught");
        });
        thrower.setUncaughtExceptionHandler((t, e) -> uncaught[0] = e);
        thrower.start();
        thrower.join();
        check(uncaught[0] instanceof IllegalArgumentException, "uncaught exception not handled: " + uncaught[0]);

        System.out.println(failure == null ? "probe passed" : "probe failed: " + failure);
    }
}
