/**
 * Holds 50 MB and parks a thread on a monitor for good, then prints "probe ready". Loaded several
 * times in one tab (tools: a CDP script reloading it and reading the heap after a collection), it
 * shows whether a reloaded page's module, its objects and its suspended threads are freed. With
 * the Fast build (JSPI, {@code -Pteavm.fast}) a thread parked forever is a suspended engine stack.
 *
 * <p>What it found (2026-10-05, Chromium 151 and 153): the coroutine build's heap stayed under
 * 2 MB over five loads (its parked thread holds nothing the engine sees), the JSPI build's grew
 * 50 MB a load: V8 never frees a suspended stack, so every earlier page stayed whole. Ending the
 * fibers on {@code pagehide} (jspi.ts) keeps it at the one live page's 51 MB.
 */
public final class ReloadLeakProbe {

    static final Object LOCK = new Object();
    static byte[][] held;

    public static void main(String[] args) throws InterruptedException {
        held = new byte[50][];
        for (int i = 0; i < held.length; i++) {
            held[i] = new byte[1 << 20];
            held[i][i] = (byte) i;
        }
        Thread parked = new Thread(() -> {
            synchronized (LOCK) {
                while (true) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "parked");
        parked.setDaemon(true);
        parked.start();
        Thread.sleep(50);
        System.out.println("probe ready " + held[7][7]);
    }
}
