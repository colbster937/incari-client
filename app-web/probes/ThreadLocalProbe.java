/**
 * web-classlib's {@code ThreadLocal} in the browser: four threads take turns through sleeps (each
 * wait switches threads, so each thread-local's remembered last thread keeps changing), each
 * setting and reading its own value, with {@code withInitial}'s value per thread and after
 * {@code remove}. Prints "probe passed". Run it in both builds.
 */
public final class ThreadLocalProbe {

    private static final ThreadLocal<String> NAME = new ThreadLocal<>();

    private static final ThreadLocal<int[]> COUNTER = ThreadLocal.withInitial(() -> new int[1]);

    static volatile String failure;

    private ThreadLocalProbe() {}

    static void check(boolean condition, String what) {
        if (!condition && failure == null) {
            failure = what;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        NAME.set("main");
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            String name = "worker-" + i;
            threads[i] = new Thread(() -> {
                check(NAME.get() == null, name + " saw another thread's value");
                NAME.set(name);
                for (int n = 0; n < 50; n++) {
                    COUNTER.get()[0]++;
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    check(name.equals(NAME.get()), name + " read " + NAME.get() + " after a switch");
                }
                check(COUNTER.get()[0] == 50, name + " counted " + COUNTER.get()[0]);
                NAME.remove();
                check(NAME.get() == null, name + " still had a value after remove");
            }, name);
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        check("main".equals(NAME.get()), "main read " + NAME.get());
        check(COUNTER.get()[0] == 0, "main's counter was " + COUNTER.get()[0]);
        System.out.println(failure == null ? "probe passed" : "probe failed: " + failure);
    }
}
