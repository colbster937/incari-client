/**
 * Regression probe for tools/webbuild's MonitorElision routing every {@code Object.wait} through
 * web-classlib's {@code Waits}, which times the render thread's waits: plain, timed and
 * nanosecond waits still wait, wake on a notify from another thread, and time out; a join and a
 * CompletableFuture's join (which wait underneath) still return. The first build that routed waits
 * also routed Object's own wait(long) call to wait(long, int), which recursed until the stack ran
 * out at start. Prints "probe passed".
 */
public final class WaitRoutingProbe {

    private static final Object LOCK = new Object();
    private static boolean ready;

    private WaitRoutingProbe() {}

    public static void main(String[] args) throws Exception {
        // A timed wait that times out.
        long start = System.currentTimeMillis();
        synchronized (LOCK) {
            LOCK.wait(30);
            LOCK.wait(5, 500);
        }
        long waited = System.currentTimeMillis() - start;
        // A wait that another thread's notify ends.
        Thread notifier = new Thread(() -> {
            synchronized (LOCK) {
                ready = true;
                LOCK.notifyAll();
            }
        });
        notifier.start();
        synchronized (LOCK) {
            while (!ready) {
                LOCK.wait();
            }
        }
        notifier.join();
        // A future completed on another thread, joined here.
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        new Thread(() -> future.complete("done")).start();
        String joined = future.join();
        if (waited >= 30 && ready && joined.equals("done")) {
            System.out.println("probe passed");
        } else {
            System.out.println("probe failed: waited " + waited + " ms, ready " + ready + ", joined " + joined);
        }
    }
}
